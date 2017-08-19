# HotFixDemo
该项目是一个Gradle插件，实现android的热修复
该项目是在研究Nuwa,Instant-Run等热修复的源码的基础上，通过实现Gradle(1.5以上)插件来实现Android的热更新。该项目是个人的研究，主要目的是通过实践加深对Gradle的生命周期，Android打包，Groovy编程等基本技能的理解和应用。<br/>

##热修复的基本原理
* 1 Instant-Run是Google开发的一个热修复插件，它的基本原理是通过字节码技术，对每一个class生成一个class$change的代理类，当检查某有一个class的方法有修改时，
那么Instant-Run将对该方法插入一个$overwrite变量。然后在下一个启动时，如果检测到$overwrite不为空，那么将调用改类的代理类即class$change。这样就实现了热修复。

* 2 Nuwa是另一个热修复框架，该框架的主要原理是基于QQ控件团队开源的热修复的原理。该原理总结起来就是将补丁包插入到DexElements数组的第一个位置，此外还需要觉接
的难点就是怎么避开Android系统的校验，也就是如果A类直接或者间接引用B类，而且A类和B类在同一个Dex，那么将报错。针对该问题通过字节码技术，对每一个类直接引用
一个Hack.class类，然后将Hack.class作为一个单独的Dex，插入到DexElements的第一个位置。类似的热修复开源框架有Robust，RocooFix等。

* 3 AndFix是另一种思路的热修复，它的基本原理是通过JNI技术，修改方法在虚拟机的地址，该开源项目我将在NDK的研究中进行另外说明。

总结起来，大部分的热修复框架，或多或少都设计到Gradle插件开发的技术。所以如果你要自己开发一个基于Gradle插件的热修复框架你需要先明白Gradle的插件化基本实现
步骤，这是我在研究过程中阅读的文章，当然还有很多关于这一方面的好文章，我的建议是自己按照文章中的思路自己实现一个简单的Gradle插件。

> http://www.jianshu.com/p/417589a561da <br/>
> http://blog.csdn.net/sbsujjbcy/article/details/50782830/ <br/>
> http://www.jianshu.com/p/d53399cd507b <br/>
> http://www.jianshu.com/p/3c59eded8155 <br/>
> http://www.jianshu.com/p/f95f3d0e4b24 <br/>

<br/>
另外，我们需要明白Gralde执行的基本生命周期，这里总结一下我认为比较有用的要点。<br/>

* 1. rootproject 的setting.gradle,然后是rootproject的build.gradle,然后是各个subproject。所以project下的build.gradle会先于app下的build.gradle。
* 2. 在解析setting.gradle之后，开始解析build.gradle之前，这里如果要干些事情（更改build.gradle校本内容），可以写在beforeEvaluate 举个例子，我们将
我们的一个subproject中的apply plugin改掉，原来是一个library工程，我们希望它被当作application处理;
* 3. 在所有build.gradle解析完成后，开始执行task之前，此时所有的脚本已经解析完成，task，plugins等所有信息可以获取，task的依赖关系也已经生成，如果此
时需要做一些事情，可以写在afterEvaluate;
* 4. 每个task都可以定义doFirst，doLast，用于定义在此task执行之前或之后执行的代码.

## HotFixDemo原理及其基本流程

* 1 我们定义了一个插件**HotFixPlugin**，以及一个Transform即**HotFixTransform**.在**HotFixPlugin**中主要的代码逻辑是:<br/>
  * 1.1 初始化HotFix的Extension,以便用户能够灵活的根据自己的需求配置，该Extension的定义如下:<br/>
  
```groovy

public class HotFixExtension {
    //补丁需要考虑的包
    @Input
    HashSet<String> includePackage = []
    //补丁不需要考虑的类
    @Input
    HashSet<String> excludeClass = []
    //上一次的版本号
    @Input
    String preVersion;
    //当前的版本号
    @Input
    String currentVersion;
    @Input
    boolean enable = false
    HotFixExtension(Project project) {
    }
}

```
各个变量的作用在注释中有说明，这里需要补充一些，includePackage和excludeClass中包名的分割都是用的.分割。另外我们将在build/outputs/buildTypeAndFlavor/version(其中version是preVersion和currentVersion)
的取值)保存补丁操作需要保存的hash.txt,mapping.txt,以及最终的补丁patch.jar。具体patch.jar的生成详见后文。<br/>

   * 1.2 构建一个**copyMappingClosure**闭包，然后hook住混淆Task即**ProguardTask**。注意这行代码:<br/>
   
   ```groovy

    proguardTask.doLast(copyMappingClosure)

   ```
   如上文所述，在**ProguardTask**执行完毕之后**copyMappingClosure**闭包将执行，闭包的主要作用是将混淆**ProguardTask**生成的文件(主要包括了proguard-android.txt,
   proguard-rules.pro,proguard.txt,aapt_rules.txt)等文件。然后将这些文件保存到一个全局结合中，这些文件在**HotFixTransform**任务手动执行混淆的时候
   需要引入这些文件。这是因为Transform的执行顺序为**HotFixTransform**=>>**ProguardTransform**=>>**DexTransform**，在上一次打包过程中根据proguad-rules将
   需要的文件都进行了混淆。可是我们现在需要在**HotFixTransform**任务中通过dex命令生成补丁，所以我们需要将当前的文件先手动进行混淆，这样生成的补丁才能够
   生效。所以我们要前一次的所有混淆文件拷贝到指定文件，在执行当前混淆前将其读入内存中。
   
   * 2 **HotFixTransform**的基本逻辑
   在自定义的Transform中，我们主要关注一下方法
   
   
   ```groovy

     void transform(Context context, Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider, boolean isIncremental)
            throws IOException, TransformException, InterruptedException {
            
            
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
         ....   
        }

   ```  
       
   主要该方法中我们需要关注**Collection<TransformInput> inputs**和**TransformOutputProvider outputProvider**两个参数。这两个参数表明了Transform的
   输入输出流，我们以**HotFixTransform**=>>**ProguardTransform**=>>**DexTransform**三个Transform为例，前一个Transform的输出是下一个Transform的输入。
   首先需要说明的是** HotFixInjectUtil.appendClassPath(hackFile.absolutePath)**，它的作用是将**AntilazyLoad**类加入到javaassist的依赖classpath，这样我们
   在为其他class注入时，引用**AntilazyLoad**不会包con't find AntilazyLoad.class,另外为了解决CLASS_ISPREVERIFIED问题吗，我们对每一个class插入了AntilazyLoad的直接引用，
   但是在执行**ProguardTransform**任务时，如果没有AntilazyLoad类，那么也将会报class not found的错误。为此有人提出一个可行的解决方案:<br/>
   > http://blog.csdn.net/u010386612/article/details/51192421 <br/>
   
   该文章解决混淆的主要原理就是在对class进行注入之前，将所有的class保存到一个临时目录下，那么如果发现有混淆那么将临时保存的目录下文件拷贝出来，这样就间接将
   注入代码移除了。但是该方法比较繁琐，那么有没一种比较简单的思路了？为此我们需要研究一下ProguardTransform的源码。另外，该系列也同样一个热修复系列，但是在最后作者放弃了Transform的方式。
   
   * 3 **ProGuardTransform**的源码分析
        **ProGuardTransform**继承了**BaseProguardAction**类，并且实现了Transform抽象类。我们关注**ProGuardTransform**里面几个比较关键的方法:
        
   ```groovy

    @Override
    public void transform(@NonNull final TransformInvocation invocation) throws TransformException {
        // only run one minification at a time (across projects)
        SettableFuture<TransformOutputProvider> resultFuture = SettableFuture.create();
        final Job<Void> job = new Job<>(getName(),
                new com.android.builder.tasks.Task<Void>() {
                    @Override
                    public void run(@NonNull Job<Void> job,
                            @NonNull JobContext<Void> context) throws IOException {
                        doMinification(
                                invocation.getInputs(),
                                invocation.getReferencedInputs(),
                                invocation.getOutputProvider());
                    }

                    @Override
                    public void finished() {
                        resultFuture.set(invocation.getOutputProvider());
                    }

                    @Override
                    public void error(Exception e) {
                        resultFuture.setException(e);
                    }
                }, resultFuture);
        try {
            SimpleWorkQueue.push(job);

            // wait for the task completion.
            try {
                job.awaitRethrowExceptions();
            } catch (ExecutionException e) {
                throw new RuntimeException("Job failed, see logs for details", e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

   ```
   可以看到transform方法里面通过TransformInvocation代理类拿到了输入出入流，然后直接调用了**doMinification**方法，注意这里**invocation.getInputs()**
  就是ProguardTransform的前一个Transform的输出，也就是我们自定的HotFixTransform的输出流。那么**doMinification**究竟做了什么，直接来看:<br/>
  
  ```groovy

     private void doMinification(
            @NonNull Collection<TransformInput> inputs,
            @NonNull Collection<TransformInput> referencedInputs,
            @Nullable TransformOutputProvider output) throws IOException {
        checkNotNull(output, "Missing output object for transform " + getName());
        Set<ContentType> outputTypes = getOutputTypes();
        Set<Scope> scopes = getScopes();
        File outFile = output.getContentLocation("main", outputTypes, scopes,
                asJar ? Format.JAR : Format.DIRECTORY);
        if (asJar) {
            mkdirs(outFile.getParentFile());
        } else {
            mkdirs(outFile);
        }

        try {
            GlobalScope globalScope = variantScope.getGlobalScope();

            // set the mapping file if there is one.
            File testedMappingFile = computeMappingFile();
            if (testedMappingFile != null) {
                applyMapping(testedMappingFile);
            }

            // --- InJars / LibraryJars ---
            addInputsToConfiguration(inputs, false);
            addInputsToConfiguration(referencedInputs, true);

            // libraryJars: the runtime jars, with all optional libraries.
            for (File runtimeJar : globalScope.getAndroidBuilder().getBootClasspath(true)) {
                libraryJar(runtimeJar);
            }

            // --- Out files ---
            outJar(outFile);

            // proguard doesn't verify that the seed/mapping/usage folders exist and will fail
            // if they don't so create them.
            mkdirs(proguardOut);

            for (File configFile : getAllConfigurationFiles()) {
                applyConfigurationFile(configFile);
            }

            configuration.printMapping = printMapping;
            configuration.dump = dump;
            configuration.printSeeds = printSeeds;
            configuration.printUsage = printUsage;

            forceprocessing();
            runProguard();

            if (!asJar) {
                // if the output of proguard is a folder (rather than a single jar), the
                // dependencies will be written as jar in the same folder output.
                // So we move it to their normal location as new jar outputs.
                File[] jars = outFile.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File file, String name) {
                        return name.endsWith(DOT_JAR);
                    }
                });
                if (jars != null) {
                    for (File jarFile : jars) {
                        String jarFileName = jarFile.getName();
                        File to = output.getContentLocation(
                                jarFileName.substring(0, jarFileName.length() - DOT_JAR.length()),
                                outputTypes, scopes, Format.JAR);
                        mkdirs(to.getParentFile());
                        renameTo(jarFile, to);
                    }
                }
            }

        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }

            throw new IOException(e);
        }
    }

   ```
   
<br/>直接挑我们需要关注的方法，就是**addInputsToConfiguration**，该方法最终的调用链为**addInputsToConfiguration()**=>**handleQualifiedContent()**=>
**inputJar()**，**inputJar()**最终将该Transform的所有输入文件添加到ProguardTransform的依赖路径(也就是Configuration.libraryJars)。所以我们的方案
可以是将AntilazyLoad类，作为**HotFixTransform**的输出，这样它直接能够添加到ProguardTransform的依赖(这也是上文中代码所展示的那样)。那么除了该方法之外还有没有
其他的方法了?为此我们继续看**doMinification**方法中的其他代码<br/>

   ```groovy

     for (File runtimeJar : globalScope.getAndroidBuilder().getBootClasspath(true)) {
                libraryJar(runtimeJar);
            }

   ```
   这行代码我猜就是将BootClasPath下的所有jar直接加入Configuration.libraryJars中，注意**libraryJar**方法实际上最终也是调用了**inputJar()**。但是注意
   的是libraryJar方法是protected修饰的，所以我们需要通过反射来hook主该方法，该方案通过我的实践证明也是可行的。
   
   * 3 代码注入
   明白了混淆的原理之后，我们回到HotFixTransform中。接下来主要的工作就是要分开遍历Transform的inputs，它有两种类型，一种是目录，一种是jar包。这里具体说明
   一下对目录的代码注入过程。<br/>
   
   
   ```groovy

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


   ```
   主要过程注释已经说明了，简单说就是过滤掉不符合条件的class,如果符合条件调用**referHackByJavassistWhenInit**方法注入代码。然后计算该文件的hash值，
   通过比较之前的hash值，如果hash值发生了变化，表明该文件需要进行补丁操作，直接将其拷贝到patchDir，后续调用dex命令将其生成patch.jar补丁文件。
   
   ## 总结
   HotFix不建议在项目中使用，因为不支持资源文件，以及so的热修复。但是如果你想分析其他优秀的热修复框架，这是一个不错的学习样本。最后，需要补充的是Gradle的
   调试比较麻烦，而且相比于java或者android开发来说，需要注意很多细小的问题，所以建议自己动手去实践，其实原理说来说去就是那些。目前，我正在考虑将MultiDex原理
   和它结合，因为分包也可以通过Gradle的插件来实现。
   









 
