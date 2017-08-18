package com.yff.hotfix

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.utils.IOUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.objectweb.asm.*

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class UnifyRTransform extends Transform {


    private String dpPackagePrefix
    private String libDrawableClass
    private final Project project

    public UnifyRTransform(Project project) {
        this.project = project
    }

    @Override
    String getName() {
        return "UnifyR"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }


    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }


    @Override
    boolean isIncremental() {
        return false;
    }


    @Override
    void transform(Context context, Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider, boolean isIncremental)
            throws IOException, TransformException, InterruptedException {
        project.logger.error "==========================transform==================="
        //1. 读取extension里面的值
        def extension = project.extensions.findByName("UnifyR") as UnifyRExtension
        dpPackagePrefix = extension.dpPackagePrefix
        libDrawableClass = extension.libDrawableClass
        project.logger.error "dpPackagePrefix = " + dpPackagePrefix
        project.logger.error "libDrawableClass = " + libDrawableClass
        //2.获取Manifest文件
        //获取buildType
        String buildAndFlavor = context.path.split("transformClassesWithUnifyRFor")[1];
        project.logger.error "buildType = " + buildAndFlavor
        //获取processManifestTask
        def processManifestTask = project.tasks.findByName("process${buildAndFlavor}Manifest")
        //注意这里获取的索引
        def manifestFile = processManifestTask.outputs.files.files[1]
        def packageName = new XmlParser().parse(manifestFile).attribute('package')
        def rootPackagePrefix = packageName.replace('.', '/') + '/'
        project.logger.error "packageName = " + packageName + "; rootPackagePrefix = " + rootPackagePrefix

        /**
         * 遍历输入文件
         */
        inputs.each { TransformInput input ->
            /**
             * 遍历目录
             */
            input.directoryInputs.each { DirectoryInput directoryInput ->
                /**
                 * 获得产物的目录
                 */
                File dest = outputProvider.getContentLocation(directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY);
                String buildTypes = directoryInput.file.name
                String productFlavors = directoryInput.file.parentFile.name
                //这里进行我们的处理 TODO
                processClass(directoryInput.file.absolutePath, rootPackagePrefix);
//                project.logger.error "Copying ${directoryInput.name} to ${dest.absolutePath}"
                /**
                 * 处理完后拷到目标文件
                 */
                FileUtils.copyDirectory(directoryInput.file, dest);
            }

            /**
             * 遍历jar
             */
            input.jarInputs.each { JarInput jarInput ->
                String destName = jarInput.name;
                /**
                 * 重名名输出文件,因为可能同名,会覆盖
                 */
                def hexName = DigestUtils.md5Hex(jarInput.file.absolutePath);
                if (destName.endsWith(".jar")) {
                    destName = destName.substring(0, destName.length() - 4);
                }
                /**
                 * 获得输出文件
                 */
                File dest = outputProvider.getContentLocation(destName + "_" + hexName, jarInput.contentTypes, jarInput.scopes, Format.JAR);
                processJar(jarInput.file, dest.getName(), rootPackagePrefix);

                //将输入内容复制到输出
                FileUtils.copyFile(jarInput.file, dest)
//                project.logger.error "Copying ${jarInput.file.absolutePath} to ${dest.absolutePath}"
            }
        }
    }

    private void processClass(String path, String rootPackagePrefix) {
        File dir = new File(path);
        if (dir.isDirectory()) {
            dir.eachFileRecurse {file->
                String filePath = file.absolutePath;
                System.out.println "正在处理的文件 filePath = " + filePath
                if(filePath.contains(dpPackagePrefix) && filePath.endsWith(".class") && filePath.contains('R$')) {
                    def tmpFile = new File(file.getParent(), file.name + ".tmp")
                    FileInputStream inputStream = new FileInputStream(file);
                    FileOutputStream outputStream = new FileOutputStream(tmpFile)

                    def bytes = unifyR(inputStream, rootPackagePrefix);
                    outputStream.write(bytes)
                    inputStream.close()
                    outputStream.close()
                    if (file.exists()) {
                        file.delete()
                    }
                    tmpFile.renameTo(file)
                }
            }
        }
    }

    /**
     * 解压该路径下的jar，并修改jar下每一个class文件
     * @param jarFile
     * @param destFileName :输出文件名
     */
    private void processJar(File file, String destFileName, String rootPackagePrefix) {
        if (file && file.absolutePath.endsWith(".jar")) {
            //遍历jar里面的entry
            def jarFile = new JarFile(file);
            Enumeration<JarEntry> enumeration = jarFile.entries();
            //输入的临时文件
            File tmpFile = new File(file.getParent(), destFileName + ".tmp");
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tmpFile))
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = enumeration.nextElement();
                String entryName = jarEntry.name;
                ZipEntry zipEntry = new ZipEntry(entryName)
                InputStream inputStream = jarFile.getInputStream(jarEntry)
                jarOutputStream.putNextEntry(zipEntry);
                //如果该entry在我们指定的包下，并且是资源文件
                if (entryName.startsWith(dpPackagePrefix) &&
                        entryName.endsWith(".class") &&
                        entryName.contains('R$')) {
                    System.out.println "正在处理字节码的entryName = " + entryName;
                    jarOutputStream.write(unifyR(inputStream, rootPackagePrefix));
                } else {
                    //将原始的jar文件流输入到指定目录
                    jarOutputStream.write(IOUtils.toByteArray(inputStream));
                }
                jarOutputStream.closeEntry();
            }//end while
            jarOutputStream.close();
            jarFile.close();
            tmpFile.renameTo(file);
        }//end if
    }

    private byte[] unifyR(InputStream inputStream, String rootPackagePrefix) {
        ClassReader cr = new ClassReader(inputStream);
        ClassWriter cw = new ClassWriter(cr, 0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM4, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
                mv = new MethodVisitor(Opcodes.ASM4, mv) {
                    @Override
                    void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                        if (owner.contains(dpPackagePrefix) && owner.contains("R\$") && !owner.contains(rootPackagePrefix)) {
                            super.visitFieldInsn(opcode, rootPackagePrefix + "R\$" + owner.substring(owner.indexOf("R\$") + 2), fName, fDesc);
                        } else {
                            super.visitFieldInsn(opcode, owner, fName, fDesc);
                        }
                    }
                }
                return mv;
            }

        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}

