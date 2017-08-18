package com.yff.hotfix.utils

import org.apache.tools.ant.util.JavaEnvUtils
import org.gradle.api.logging.Logger
import org.gradle.internal.impldep.org.codehaus.plexus.interpolation.os.Os
import org.objectweb.asm.*
import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.utils.IOUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * 使用javaassit修改class文件
 */
public class HotFixInjectUtil {

    private static ClassPool pool = ClassPool.getDefault()

    /**
     * 添加classPath到ClassPool
     * @param libPath
     */
    public static void appendClassPath(String libPath) {
        pool.appendClassPath(libPath)
    }

    /**
     * 向path对应的class文件注入static代码。
     * @param project
     * @param path :需要注入class的全路径
     * @param destHashFile :该文件的一行记录该class的hash值
     * @param hashMap :上一次所有满足要求的文件的hash值，在transform初始化的时候我们将其读入map内存，其中key是class的全路径
     * @param patchDir :比较两次同一个class的hash值不一致,如果不一致，那么将修复后的class写入该目录，为后续制作补丁使用
     * @param buildType :本次打包的构建类型
     * @param productFlavors :本次打包的渠道名
     * @param includePackage :需要处理的包，通过该条件过滤掉不需要注入的包下的所有class，比如hotfixcore下面的以及以下其他而外第三方框架
     * @param excludeClass :需要去除的类，因为我们在application里面注入补丁，所以该类不应该被打补丁
     */
    public
    static void injectHotFix(Project project, String path, File destHashFile, Map hashMap, File patchDir,
                             String buildType, String productFlavors, Set<String> includePackage,
                             Set<String> excludeClass) {
        pool.appendClassPath(path)
        File dir = new File(path);
        if (dir.isDirectory()) {
            dir.eachFileRecurse {
                File file ->
                    String filePath = file.absolutePath;
                    if (shouldProcessClass(filePath, includePackage, excludeClass)) {
                        //生成临时文件
                        def optClass = new File(file.getParent(), file.name + ".opt")
                        FileInputStream inputStream = new FileInputStream(file);
                        FileOutputStream outputStream = new FileOutputStream(optClass)
                        def bytes = referHackByJavassistWhenInit(pool, inputStream);
                        //将修改后的文件写入临时文件
                        outputStream.write(bytes)
                        inputStream.close()
                        outputStream.close()
                        //删除原始文件
                        if (file.exists()) {
                            file.delete()
                        }
                        //将修改后的文件写入原始文件，覆盖的目的就是为后续dex做成补丁做准备
                        optClass.renameTo(file)
                        def hash = DigestUtils.shaHex(bytes)
                        //获取class的全路径，以便作为该文件hash值的key，保存到map中
                        String className = parseClass(filePath, includePackage);

                        if (className) {
                            String classNameKey = flatFilePath(className);
                            destHashFile.append(NuwaMapUtils.format(classNameKey, hash))
                            if (NuwaMapUtils.notSame(hashMap, classNameKey, hash)) {
                                //注意在将修改后的字节流写入目标文件时，文件必须是以.class结尾
                                project.logger.error "该文件需要补丁处理 : " + file.absolutePath
                                project.logger.error "className : " + className
                                NuwaFileUtils.copyBytesToFile(bytes, NuwaFileUtils.touchFile(patchDir, className))
                            }
                        }
                    } else {
                        project.logger.error "该文件不需要处理" + filePath
                    }
            }
        }
    }

    /**
     * 过滤掉不需要处理的.jar
     * @param path
     * @return
     */
    public static boolean shouldProcessJar(String path) {
        return path.endsWith("classes.jar") &&
                !path.contains("com/android/support") &&
                !path.contains("/android/m2repository");
    }

    /**
     * 注意所有的包名都统一使用/分割
     * @param entryName
     * @param includePackage
     * @param excludeClass
     * @return
     */
    private
    static boolean shouldProcessClass(String entryName, HashSet<String> includePackage, HashSet<String> excludeClass) {
        String className = flatFilePath(entryName)
        return className.endsWith(".class") &&
                !className.contains('R$') &&
                !className.contains('R.class') &&
                !className.contains("BuildConfig.class") &&
                !className.contains("android.support") &&
                !className.contains("com.richfit.hotfixcore") &&
                NuwaSetUtils.isIncluded(className, includePackage) &&
                !NuwaSetUtils.isExcluded(className, excludeClass);
    }

    /**
     * 从path获取class的全路径
     * @param filePath :class的文件路径
     * @param includePackages :需要打包的所有包名
     * @return 这里需要返回原始文件的路径中带包名的那部分，因为它需要拷贝到最终在补丁的临时目录
     */
    private static String parseClass(String filePath, Set<String> includePackages) {
        String path = flatFilePath(filePath);
        if (includePackages) {
            for (String packageName : includePackages) {
                String pName = flatFilePath(packageName);
                if (path.contains(pName)) {
                    int index = path.indexOf(pName)
                    if (index != -1) {
//                        int end = filePath.length() - 6 // .class = 6
//                        int end = filePath.length() - 6 // .class = 6
                        String className = filePath.substring(index)
                        return className;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 将文件路径全部统一替换成.分割以便适配不同系统
     * @return
     */
    public static String flatFilePath(String filePath) {
        return filePath.replace("\\",".").replace("/",".");
    }

    /**
     * 参数说明：
     * hashFile: 本次编译所有类的“类名:hash”存放文件
     * jarFile:  jar包, 调用这个函数的地方会遍历所有的jar包
     * patchDir:  有变更的文件统一存放到这个目录里
     * map:  上一次编译所有类的hash映射
     * includePackage:  额外指定只需要注入这些包下的类
     * excludeClass： 额外指定不参与注入的类
     */
    public
    static processJar(Project project, File hashFile, File jarFile, File patchDir, Map map,
                      HashSet<String> includePackage, HashSet<String> excludeClass) {
        if (jarFile) {
            //jar的目标输出
//            def optJar = new File(jarFile.getParent(), dest.name)
            def optJar = new File(jarFile.getParent(), jarFile.name + ".opt")
            def file = new JarFile(jarFile);
            Enumeration enumeration = file.entries();
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(optJar));

            //解压
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement();
                String entryName = jarEntry.getName();
                ZipEntry zipEntry = new ZipEntry(entryName);
                //读取一个jarEntry
                InputStream inputStream = file.getInputStream(jarEntry);
                //将jar写入指定文件
                jarOutputStream.putNextEntry(zipEntry);
                // 根据一些规则和includePackage与excludeClass判断这个类要不要处理
                if (shouldProcessClass(entryName, includePackage, excludeClass)) {
                    project.logger.error "jar包解压后需要处理的class:" + entryName
                    // 拿到这个类的输入流调用这个函数完成字节码注入
                    def bytes = referHackWhenInit(inputStream);
                    jarOutputStream.write(bytes);
                    // 生成文件hash
                    def hash = DigestUtils.shaHex(bytes)
                    // 将hash值以键值对的形式写入到hash文件中，以便下次对比
                    hashFile.append(NuwaMapUtils.format(entryName, hash))
                    // 如果这个类和map中上次生成的hash不一样，则认为是修改过的，拷贝到需要最终打包的文件夹中
                    if (NuwaMapUtils.notSame(map, entryName, hash)) {
                        NuwaFileUtils.copyBytesToFile(bytes, NuwaFileUtils.touchFile(patchDir, entryName))
                    }
                } else {
                    // 如果这个类不处理则直接写进opt文件
                    jarOutputStream.write(IOUtils.toByteArray(inputStream));
                }
                jarOutputStream.closeEntry();
            }
            jarOutputStream.close();
            file.close();

            if (jarFile.exists()) {
                jarFile.delete()
            }
            optJar.renameTo(jarFile)
        }
    }


    private
    static byte[] referHackByJavassistWhenInit(ClassPool classPool, InputStream inputStream) {
        CtClass clazz = classPool.makeClass(inputStream)
        CtConstructor ctConstructor = clazz.makeClassInitializer()
        ctConstructor.insertAfter("if(Boolean.FALSE.booleanValue()){System.out.println(com.richfit.hack.AntilazyLoad.class);}")
        def bytes = clazz.toBytecode()
        clazz.defrost()
        return bytes
    }


    public static signedApk(Logger logger, def variant, File apkFile) {
        if (!apkFile.exists())
            return;

        def signingConfigs = variant.getSigningConfig()
        if (signingConfigs == null) {
            logger.error "no need to sign"
            return;
        }


        def args = [JavaEnvUtils.getJdkExecutable('jarsigner'),
                    '-verbose',
                    '-sigalg', 'MD5withRSA',
                    '-digestalg', 'SHA1',
                    '-keystore', signingConfigs.storeFile,
                    '-keypass', signingConfigs.keyPassword,
                    '-storepass', signingConfigs.storePassword,
                    apkFile.absolutePath,
                    signingConfigs.keyAlias]

        def proc = args.execute()
    }


    public static zipalign(Project project, File apkFile) {
        if (apkFile.exists()) {
            def sdkDir
            Properties properties = new Properties()
            File localProps = project.rootProject.file("local.properties")
            if (localProps.exists()) {
                properties.load(localProps.newDataInputStream())
                sdkDir = properties.getProperty("sdk.dir")
            } else {
                sdkDir = System.getenv("ANDROID_HOME")
            }
            if (sdkDir) {
                def cmdExt = Os.isFamily(Os.FAMILY_WINDOWS) ? '.exe' : ''
                File dest = new File("${apkFile.absolutePath}.zipalign");
                def argv = []
                argv << '-f'    //overwrite existing outfile.zip
                // argv << '-z'    //recompress using Zopfli
                argv << '-v'    //verbose output
                argv << '4'     //alignment in bytes, e.g. '4' provides 32-bit alignment
                argv << apkFile.absolutePath

                argv << dest.absolutePath  //output

                project.exec {
                    commandLine "${sdkDir}/build-tools/${project.android.buildToolsVersion}/zipalign${cmdExt}"
                    args argv
                }

                if (apkFile.exists()) {
                    apkFile.delete()
                }
                dest.renameTo(apkFile)
            } else {
                throw new RuntimeException('$ANDROID_HOME is not defined')
            }
        }
    }
}