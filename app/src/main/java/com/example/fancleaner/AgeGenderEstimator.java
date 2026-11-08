package com.example.fancleaner;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 年龄 + 性别估计（TFLite）。
 *
 * 模型来自 shubham0204/Age-Gender_Estimation_TF-Android：
 *   - model_age_nonq.tflite     输入 [1,200,200,3] float32（/255），输出 [1,1]，年龄 = out * 116
 *   - model_gender_nonq.tflite  输入 [1,128,128,3] float32（/255），输出 [1,2]，[0]=男性概率，[1]=女性概率
 *
 * 存放位置：app/src/main/assets/ 下，与类名无关，靠文件名加载。
 */
public class AgeGenderEstimator {

    private static final String TAG = "AgeGenderEstimator";

    private static final int AGE_SIZE = 200;
    private static final int GENDER_SIZE = 128;
    private static final float AGE_SCALE = 116f;

    public static class Result {
        public final float age;
        public final boolean isFemale;

        Result(float age, boolean isFemale) {
            this.age = age;
            this.isFemale = isFemale;
        }
    }

    private Interpreter ageInterpreter;
    private Interpreter genderInterpreter;

    public AgeGenderEstimator(Context context) {
        try {
            ageInterpreter = new Interpreter(loadModel(context, "model_age_nonq.tflite"),
                    new Interpreter.Options().setNumThreads(2));
            genderInterpreter = new Interpreter(loadModel(context, "model_gender_nonq.tflite"),
                    new Interpreter.Options().setNumThreads(2));
            Log.i(TAG, "年龄/性别模型加载完成");
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败（检查 assets 里是否有 tflite 文件）", e);
            ageInterpreter = null;
            genderInterpreter = null;
        }
    }

    public boolean isReady() {
        return ageInterpreter != null && genderInterpreter != null;
    }

    /** 输入一张人脸（最好是人脸特写），返回年龄与性别；失败返回 null */
    public Result estimate(Bitmap face) {
        if (!isReady() || face == null) return null;
        try {
            Bitmap ageInput = Bitmap.createScaledBitmap(face, AGE_SIZE, AGE_SIZE, true);
            float[][] ageOut = new float[1][1];
            ageInterpreter.run(toFloatBuffer(ageInput, AGE_SIZE), ageOut);

            Bitmap genderInput = Bitmap.createScaledBitmap(face, GENDER_SIZE, GENDER_SIZE, true);
            float[][] genderOut = new float[1][2];
            genderInterpreter.run(toFloatBuffer(genderInput, GENDER_SIZE), genderOut);

            float age = ageOut[0][0] * AGE_SCALE;
            boolean isFemale = genderOut[0][0] <= genderOut[0][1]; // [0]=男, [1]=女
            Log.d(TAG, "年龄≈" + age + " 女性=" + isFemale);
            return new Result(age, isFemale);
        } catch (Exception e) {
            Log.w(TAG, "推理失败", e);
            return null;
        }
    }

    private ByteBuffer toFloatBuffer(Bitmap bitmap, int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder());
        int[] pixels = new int[size * size];
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255f); // R
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255f);  // G
            buffer.putFloat((pixel & 0xFF) / 255f);         // B
        }
        buffer.rewind();
        return buffer;
    }

    private ByteBuffer loadModel(Context context, String name) throws IOException {
        try (InputStream is = context.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) {
                bos.write(chunk, 0, n);
            }
            byte[] bytes = bos.toByteArray();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            buffer.put(bytes);
            buffer.rewind();
            return buffer;
        }
    }

    public void close() {
        try {
            if (ageInterpreter != null) ageInterpreter.close();
            if (genderInterpreter != null) genderInterpreter.close();
        } catch (Exception ignored) {
        }
    }
}
