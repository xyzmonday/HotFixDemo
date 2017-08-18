package com.richfit.hotfixcore.utils;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by jixin.jia on 15/10/31.
 */
public class AssetUtils {

    public static String copyAsset(Context context, String assetName, File dir) throws IOException {
        File outFile = new File(dir, assetName);
        //注意这里Hack.jar只能加载一次
        if (!outFile.exists()) {
            AssetManager assetManager = context.getAssets();
            InputStream in = assetManager.open(assetName);
            OutputStream out = new FileOutputStream(outFile);
            copyFile(in, out);
            in.close();
            out.close();
        }
        return outFile.getAbsolutePath();
    }

    public static String copyFile(File inFile, File outFile) throws IOException {
        //保证仅仅加载一次
        if (!outFile.exists()) {
            InputStream in = new FileInputStream(inFile);
            OutputStream out = new FileOutputStream(outFile);
            copyFile(in, out);
            in.close();
            out.close();
            //删除补丁文件
//            if (inFile.exists())
//                inFile.delete();
        }
        return outFile.getAbsolutePath();
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
