package com.yff.hotfix

import org.gradle.api.Project
import org.gradle.api.tasks.Input

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
    @Input
    String currentVersion;
    @Input
    boolean enable = false
    HotFixExtension(Project project) {
    }
}