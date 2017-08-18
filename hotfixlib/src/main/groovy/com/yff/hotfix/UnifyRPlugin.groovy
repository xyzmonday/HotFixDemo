package com.yff.hotfix

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
/**
 * 而ADT14以及更高的版本中对Library资源处理时，
 * Library的R资源不再是static final的了，详情请查看google官方说明，
 * 这样在最终打包时Library中的R没法做到内联，这样带来了R field过多的情况。
 * 这里的解决方案是通过遍历class文件，修改字节码的方式实现Field过多现象，
 * 注意这需要考虑是否开启multiDex
 */
public class UnifyRPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.logger.error "开始修改资源文件......"
        //创建扩展
        project.extensions.create("UnifyR", UnifyRExtension, project)
        def android = project.extensions.findByType(AppExtension)
        android.registerTransform(new UnifyRTransform(project))
    }
}