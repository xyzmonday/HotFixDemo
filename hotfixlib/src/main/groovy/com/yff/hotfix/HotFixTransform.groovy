package com.yff.hotfix

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.yff.hotfix.utils.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import proguard.*

public class HotFixTransform extends Transform {

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"
    private static final String PATCH_FILE_NAME = "patch.jar"

    private final Project project
    static HashSet<String> includePackage
    static HashSet<String> excludeClass
    static String oldHotFixDir

    public HotFixTransform(Project project) {
        this.project = project;
    }

    // 设置我们自定义的Transform对应的Task名称
    // 类似：TransformClassesWithPreDexForXXX
    @Override
    String getName() {
        return "HotFix"
    }

    // 指定输入的类型，通过这里的设定，可以指定我们要处理的文件类型
    //这样确保其他类型的文件不会传入
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    // 指定Transform的作用范围
    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    //具体的处理
    @Override
    void transform(Context context, Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider, boolean isIncremental)
            throws IOException, TransformException, InterruptedException {

        //拿到扩展值
        def extension = project.extensions.findByName("hotfix") as HotFixExtension
        includePackage = extension.includePackage
        excludeClass = extension.excludeClass
        //获取上一次的版本号
        String preVersion = extension.preVersion;
        //获取当前版本号
        String currentVersion = extension.currentVersion;

        //获取构建类型
        String buildAndFlavor = context.path.split("transformClassesWithHotFixFor")[1];

        //构建上一次补丁操作的所有文件的保存目录
        oldHotFixDir = "${project.buildDir}/outputs/hotfix/${buildAndFlavor}/${preVersion}"

        //构建本次补丁操作的所有文件的保存目录
        def outputDir = new File("${project.buildDir}/outputs/hotfix/${buildAndFlavor}/${currentVersion}")
        //本次class的hash值保存的文件
        def destHashFile = new File("${outputDir}/${HASH_TXT}")
        //本次混淆保存的map文件
        def destMapFile = new File("${outputDir}/${MAPPING_TXT}");
        //本次补丁生成的最终文件
        def destPatchJarFile = new File("${outputDir}/patch/${PATCH_FILE_NAME}");
        //本次补丁的临时目录
        def patchDir = new File("${context.temporaryDir.getParent()}/patch/")
        Map hashMap
        outputDir.mkdirs()
        patchDir = new File("${outputDir}/${buildAndFlavor}/patch")
        patchDir.mkdirs()

        //创建文件
        NuwaFileUtils.touchFile(destHashFile.getParentFile(), destHashFile.name)
        NuwaFileUtils.touchFile(destMapFile.getParentFile(), destMapFile.name)
        NuwaFileUtils.touchFile(destPatchJarFile.getParentFile(), destPatchJarFile.name)

        //找到manifest文件中的application加入 excludeClass
        def processManifestTask = project.tasks.findByName("process${buildAndFlavor}Manifest")
        //这里有坑，这里到底是去[0]还是[1]需要考虑
        def manifestFile = processManifestTask.outputs.files.files[1]
        def applicationName = NuwaAndroidUtils.getApplication(manifestFile)
        applicationName = HotFixInjectUtil.flatFilePath(applicationName)
        //注意这里读取到的是全路径com/richfit/hotfixdemo/HotFixApplication.class
        if (applicationName != null && excludeClass != null && !excludeClass.contains(applicationName)) {
            excludeClass.add(applicationName)
        }

        if (preVersion == currentVersion) {
            project.logger.error "===========上次记录的hash.txt文件已经删除=========="
            //如果当前版本号和上一次版本号一致，那么需要将老的hash.txt删除重新删除最新的，让这一次操作记录最新的hash.txt
            if (destHashFile.exists()) {
                destHashFile.delete();
            }
        }

        //读取上一次的hash文件，并去保存到内存map中，以便后续判断该文件是否
        if (oldHotFixDir) {
            def hashFile = new File("${oldHotFixDir}/${HASH_TXT}")
            hashMap = NuwaMapUtils.parseMap(hashFile)
        }

        //定义混淆时需要依赖的库
        List<File> proguardLibfiles = new ArrayList<>();

        //引入hack模块的.jar，注意这里需要的是hack模块的根目录
        File hackFile = new File("${project.project(':hack').projectDir}/hack.jar");
        HotFixInjectUtil.appendClassPath(hackFile.absolutePath)

        //注意这里我们需要将hack.jar文件拷贝到混下依赖
        proguardLibfiles.add(hackFile)
        String hackDestName = hackFile.name;
        def hexHackName = DigestUtils.md5Hex(hackFile.absolutePath);
        //去除.jar的后缀
        if (hackDestName.endsWith(".jar")) {
            hackDestName = hackDestName.substring(0, hackDestName.length() - 4);
        }
        //生成Hack.jar的保存路径
        File hackDest = outputProvider.getContentLocation(hackDestName + "_" + hexHackName,
                TransformManager.CONTENT_CLASS, TransformManager.SCOPE_FULL_PROJECT, Format.JAR);
        FileUtils.copyFile(hackFile, hackDest)

        // Transform的inputs有两种类型，一种是目录，一种是jar包，要分开遍历
        inputs.each {
            TransformInput input ->
                //对类型为“文件夹”的input进行遍历
                input.directoryInputs.each { DirectoryInput directoryInput ->
                    //加入到混淆时的依赖
                    proguardLibfiles.add(directoryInput.file)
                    //注入class,并且保存它的hash值
                    String buildType = directoryInput.file.name
                    String productFlavors = directoryInput.file.parentFile.name
                    //处理改文件
                    HotFixInjectUtil.injectHotFix(project, directoryInput.file.absolutePath, destHashFile,
                            hashMap, patchDir, buildType, productFlavors, includePackage, excludeClass)
                    // 获取output目录
                    def dest = outputProvider.getContentLocation(directoryInput.name,
                            directoryInput.contentTypes, directoryInput.scopes,
                            Format.DIRECTORY)
                    // 将input的目录复制到output指定目录
                    FileUtils.copyDirectory(directoryInput.file, dest)
                }
                //对类型为jar文件的input进行遍历
                input.jarInputs.each { JarInput jarInput ->
                    //加入到混淆时的依赖
                    proguardLibfiles.add(jarInput.file)
                    //重名名输出文件,因为可能同名,会覆盖
                    String destName = jarInput.name;
                    def hexName = DigestUtils.md5Hex(jarInput.file.absolutePath);
                    if (destName.endsWith(".jar")) {
                        destName = destName.substring(0, destName.length() - 4);
                    }
                    //获得输出文件
                    File dest = outputProvider.getContentLocation(destName + "_" + hexName,
                            jarInput.contentTypes, jarInput.scopes, Format.JAR);
                    //处理jar进行字节码注入
                    if (HotFixInjectUtil.shouldProcessJar(jarInput.file.absolutePath)) {
                        project.logger.error "正在处理的jar : " + jarInput.file.absolutePath
                        HotFixInjectUtil.processJar(project, destHashFile, jarInput.file, patchDir, hashMap,
                                includePackage, excludeClass)
                    }
                    FileUtils.copyFile(jarInput.file, dest)
                }
        }

        /**
         * 因为我们定义的Transform是在混淆的Transform之前执行的，我们拷贝出来的class是没有经过混淆的，
         * 这时候你打补丁，肯定是热修复失败的。因此我们需要判断是不是存在混淆的task，如果存在的话，
         * 我们需要手动进行混淆。 混淆的时候应用我们上面记录下来的配置文件，并且还需要应用上次发版时的mapping文件来保持类与类的对应。
         */
        if (patchDir.exists() && patchDir.listFiles() != null && patchDir.listFiles().size() != 0) {
            //如果需要混淆那么需要手动进行混淆
            project.logger.error "========正在生成补丁========"
            //是否混淆
            def proguardTask = project.tasks.findByName("transformClassesAndResourcesWithProguardFor${buildAndFlavor}")

            if (proguardTask) {
                project.logger.error "=========正在进行混淆========"
                //进行混淆
                def mappingFile = new File("${oldHotFixDir}/${MAPPING_TXT}")
                // def mappingFile = NuwaFileUtils.getVariantFile(new File("${oldHotFixDir}"), buildAndFlavor, "mapping.txt")
                Configuration configuration = new Configuration()
                configuration.useMixedCaseClassNames = false
                configuration.programJars = new ClassPath()
                configuration.libraryJars = new ClassPath()
                //应用mapping文件
                configuration.applyMapping = mappingFile;
                configuration.verbose = true
                //输出配置文件
                configuration.printConfiguration = new File("${patchDir.getParent()}/dump.txt")
                //不过滤没有引用的文件,这里一定要不过滤，不然有问题
                configuration.shrink = false
                //android 和 apache 包的依赖。 获得sdk目录
                def sdkDir
                Properties properties = new Properties()
                File localProps = project.rootProject.file("local.properties")
                if (localProps.exists()) {
                    properties.load(localProps.newDataInputStream())
                    sdkDir = properties.getProperty("sdk.dir")
                } else {
                    sdkDir = System.getenv("ANDROID_HOME")
                }
                //将android.jar和apache的库加入依赖
                if (sdkDir) {
                    def compileSdkVersion = project.android.compileSdkVersion
                    ClassPathEntry androidEntry = new ClassPathEntry(new File("${sdkDir}/platforms/${compileSdkVersion}/android.jar"), false);
                    configuration.libraryJars.add(androidEntry)
                    File apacheFile = new File("${sdkDir}/${compileSdkVersion}/platforms/optional/org.apache.http.legacy.jar")
                    //android-23下才存在apache的包
                    if (apacheFile.exists()) {
                        ClassPathEntry apacheEntry = new ClassPathEntry(apacheFile, false);
                        configuration.libraryJars.add(apacheEntry)
                    }
                }
                //将这个task的输入文件全都加入到混淆依赖的jar
                if (proguardLibfiles != null) {
                    ClassPathEntry jarFile
                    for (File file : proguardLibfiles) {
                        project.logger.error "混淆依赖 file = " + file.absolutePath
                        jarFile = new ClassPathEntry(file, false);
                        configuration.libraryJars.add(jarFile)
                    }
                }

                // 待dex未混淆的patch目录
                ClassPathEntry classPathEntry = new ClassPathEntry(patchDir, false);
                configuration.programJars.add(classPathEntry)
                // 定义混淆输出文件
                File proguardOutput = new File("${patchDir.getParent()}/proguard/")
                ClassPathEntry classPathEntryOut = new ClassPathEntry(proguardOutput, true);
                //第二个参数true代表是输出文件
                configuration.programJars.add(classPathEntryOut)

                //外部定义的混淆文件的获取并应用
                def file = HotFixPlugin.proguardConfigFile.get(buildAndFlavor);
                //这里就用到了上面一步记录下来的配置文件
                //遍历并应用
                file.each {
                    project.logger.error "proguard配置文件应用==>${it.absolutePath}"
                    ConfigurationParser proguardConfig = new ConfigurationParser(it, System.getProperties());
                    try {
                        proguardConfig.parse(configuration);
                    } finally {
                        proguardConfig.close();
                    }
                }

                //执行混淆
                ProGuard proguard = new ProGuard(configuration)
                proguard.execute()

                //对产物执行dex操作,并删除临时文件
                project.logger.error "==============执行dex生成补丁文件patch.jar==========="
                if (proguardOutput.exists()) {
                    NuwaAndroidUtils.dex(project, proguardOutput)
                    File patchFile = new File("${proguardOutput.getParent()}/${PATCH_FILE_NAME}")
                    if (patchFile.exists()) {
                        FileUtils.copyFile(patchFile, destPatchJarFile)
                        FileUtils.deleteDirectory(proguardOutput)
                        FileUtils.forceDelete(patchFile)
                    }
                    FileUtils.deleteDirectory(patchDir)
                }
            } else {
                project.logger.error "==============执行dex生成补丁文件patch.jar==========="
                NuwaAndroidUtils.dex(project, patchDir)
                File patchFile = new File("${patchDir.getParent()}/${PATCH_FILE_NAME}")
                if (patchFile.exists()) {
                    FileUtils.copyFile(patchFile, destPatchJarFile)
                    FileUtils.deleteDirectory(patchDir)
                    FileUtils.forceDelete(patchFile)
                }
            }
        }
    }
}