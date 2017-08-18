package com.yff.hotfix.utils

import org.apache.commons.io.FileUtils
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project

/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaFileUtils {

    public static File touchFile(File dir, String path) {
        def file = new File("${dir}/${path}")
        file.getParentFile().mkdirs()
        return file
    }

    public static copyBytesToFile(byte[] bytes, File file) {
        if (!file.exists()) {
            file.createNewFile()
        }
        FileUtils.writeByteArrayToFile(file, bytes)
    }


//    public static File getVariantFile(File dir, def variant, String fileName) {
//        return new File("${dir}/${variant}/${fileName}")
//    }
//
//    public static File makeFile(File dir, def variant, String fileName) {
//        return new File("${dir}/${variant}/${fileName}")
//    }
}
