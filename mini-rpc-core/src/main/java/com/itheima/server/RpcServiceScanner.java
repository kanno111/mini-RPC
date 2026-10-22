package com.itheima.server;

import com.itheima.annotation.RpcService;
import com.itheima.common.RpcException;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 扫描指定 Java 包，并创建其中所有 RPC 服务实现对象。
 *
 * <p>这个类只负责两件事：</p>
 *
 * <ol>
 *     <li>找到带有 {@link RpcService} 注解的类；</li>
 *     <li>调用这些类的公开无参构造方法，创建实现对象。</li>
 * </ol>
 *
 * <p>它不负责写入本地服务表，也不负责访问 ZooKeeper。创建出的对象会交回
 * {@link RpcProvider}，由 RpcProvider 继续完成真正的服务注册。</p>
 */
final class RpcServiceScanner {

    /** 工具类只提供静态方法，不需要创建 RpcServiceScanner 对象。 */
    private RpcServiceScanner() {
    }

    /**
     * 扫描包并创建服务实现对象。
     *
     * @param basePackage 要扫描的基础包，例如 com.itheima.example.provider
     * @return 创建完成的服务实现对象列表
     * @throws RpcException 包名无效、没有找到服务类或服务类不能实例化时抛出
     */
    static List<Object> scanAndCreateServices(String basePackage) {
        if (basePackage == null || basePackage.isBlank()) {
            throw new RpcException("RPC 服务扫描包名不能为空");
        }

        /*
         * acceptPackages 限定扫描范围。如果不限定，扫描器会检查程序的所有依赖，
         * 不但速度更慢，还可能误把其他 jar 包中的类当成业务服务。
         *
         * enableClassInfo：读取类的基本信息。
         * enableAnnotationInfo：读取类上标记的注解信息。
         */
        ClassLoader applicationClassLoader = Thread.currentThread()
                .getContextClassLoader();

        try (ScanResult scanResult = new ClassGraph()
                /*
                 * IDEA、Maven 插件等启动方式可能使用独立的应用类加载器。
                 * 明确使用当前线程的类加载器，才能扫描到 provider 模块，
                 * 而不仅仅是 JDK 或 Maven 自身的类。
                 */
                .enableClassLoaders(applicationClassLoader)
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages(basePackage.trim())
                .scan()) {

            List<Class<?>> serviceClasses = scanResult
                    .getClassesWithAnnotation(RpcService.class)
                    .stream()
                    .map(RpcServiceScanner::loadClass)
                    .sorted(Comparator.comparing(Class::getName))
                    .toList();

            if (serviceClasses.isEmpty()) {
                throw new RpcException(
                        "扫描包中没有找到带有 @RpcService 的类："
                                + basePackage
                );
            }

            List<Object> serviceObjects = new ArrayList<>();
            for (Class<?> serviceClass : serviceClasses) {
                serviceObjects.add(createServiceObject(serviceClass));
            }
            return serviceObjects;
        }
    }

    /**
     * 根据扫描结果中的全限定类名，把类真正加载进当前 JVM。
     *
     * <p>扫描阶段拿到的 {@link ClassInfo} 只是类的描述信息。只有转换成
     * {@link Class} 后，后面才能读取构造方法并创建对象。</p>
     */
    private static Class<?> loadClass(ClassInfo classInfo) {
        ClassLoader classLoader = Thread.currentThread()
                .getContextClassLoader();
        try {
            return Class.forName(classInfo.getName(), true, classLoader);
        } catch (ClassNotFoundException exception) {
            throw new RpcException(
                    "扫描到 RPC 服务后无法加载对应的类："
                            + classInfo.getName(),
                    exception
            );
        }
    }

    /**
     * 调用服务类的公开无参构造方法创建对象。
     *
     * <p>当前 mini-RPC 还不是 Spring 这样的依赖注入容器，所以暂时不知道
     * 应该为有参构造方法准备什么参数。要求公开无参构造方法，可以让对象创建
     * 规则保持明确。后续接入 Spring 时，将改为直接从 Spring 容器取得对象。</p>
     */
    private static Object createServiceObject(Class<?> serviceClass) {
        if (serviceClass.isInterface()
                || Modifier.isAbstract(serviceClass.getModifiers())) {
            throw new RpcException(
                    "@RpcService 只能标记可以创建对象的具体类："
                            + serviceClass.getName()
            );
        }

        try {
            Constructor<?> constructor = serviceClass.getConstructor();
            return constructor.newInstance();
        } catch (NoSuchMethodException exception) {
            throw new RpcException(
                    "自动创建 RPC 服务失败，服务类必须提供公开的无参构造方法："
                            + serviceClass.getName(),
                    exception
            );
        } catch (InvocationTargetException exception) {
            throw new RpcException(
                    "RPC 服务的无参构造方法执行失败："
                            + serviceClass.getName(),
                    exception.getCause()
            );
        } catch (ReflectiveOperationException exception) {
            throw new RpcException(
                    "无法创建 RPC 服务对象：" + serviceClass.getName(),
                    exception
            );
        }
    }
}
