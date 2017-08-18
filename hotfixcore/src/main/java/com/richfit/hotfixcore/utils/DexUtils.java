package com.richfit.hotfixcore.utils;

import android.util.Log;

import java.lang.reflect.Array;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

/**
 * Created by jixin.jia on 15/10/31.
 */
public class DexUtils {

    public static void injectDexAtFirst(String dexPath, String defaultDexOptPath) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        //使用DexClassLoader加载jar->dex
        DexClassLoader dexClassLoader = new DexClassLoader(dexPath, defaultDexOptPath, dexPath, getPathClassLoader());
        //getPathClassLoader是获取BaseDexClassLoader里面DexPathList实例对象pathList
        Object baseDexElements = getDexElements(getPathList(getPathClassLoader()));
        //获取新的类加载器的dexElements成员
        Object newDexElements = getDexElements(getPathList(dexClassLoader));
        Object allDexElements = combineArray(newDexElements, baseDexElements);
        //获取当前类加载器的DexPathList实例对象pathList
        Object pathList = getPathList(getPathClassLoader());
        ReflectionUtils.setField(pathList, pathList.getClass(), "dexElements", allDexElements);
        //======== 以下是测试是否成功注入 =================
        Object object = ReflectionUtils.getField(pathList,pathList.getClass(), "dexElements");
        int length = Array.getLength(object);
        Log.e("yff", "length = " + length);
    }

    private static PathClassLoader getPathClassLoader() {
        PathClassLoader pathClassLoader = (PathClassLoader) DexUtils.class.getClassLoader();
        return pathClassLoader;
    }

    private static Object getDexElements(Object paramObject)
            throws IllegalArgumentException, NoSuchFieldException, IllegalAccessException {
        return ReflectionUtils.getField(paramObject, paramObject.getClass(), "dexElements");
    }

    private static Object getPathList(Object baseDexClassLoader)
            throws IllegalArgumentException, NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        return ReflectionUtils.getField(baseDexClassLoader, Class.forName("dalvik.system.BaseDexClassLoader"), "pathList");
    }

    private static Object combineArray(Object firstArray, Object secondArray) {
        Class<?> localClass = firstArray.getClass().getComponentType();
        int firstArrayLength = Array.getLength(firstArray);
        int allLength = firstArrayLength + Array.getLength(secondArray);
        Object result = Array.newInstance(localClass, allLength);
        for (int k = 0; k < allLength; ++k) {
            if (k < firstArrayLength) {
                Array.set(result, k, Array.get(firstArray, k));
            } else {
                Array.set(result, k, Array.get(secondArray, k - firstArrayLength));
            }
        }
        return result;
    }

}
