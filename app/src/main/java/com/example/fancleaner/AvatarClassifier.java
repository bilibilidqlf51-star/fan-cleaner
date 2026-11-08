package com.example.fancleaner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;

/**
 * 头像分类器：判断一张头像是否应该被清理。
 *
 * 规则（按顺序）：
 *   1. 头像里检测不到人脸 → 清理（风景照 / 书法 / 作品图 / 图标 / 默认灰色头像）
 *   2. 有人脸 → 跑年龄+性别模型：
 *        - 女性 且 年龄 >= 40 → 清理（40多岁妈妈自拍）
 *        - 年龄 <= 14         → 清理（小孩自拍）
 *        - 其他               → 保留
 *   3. 年龄模型没加载成功 → 一律保留（保守，不误删）
 */
public class AvatarClassifier {

    private static final String TAG = "AvatarClassifier";

    /** 女性 + 年龄 ≥ 此值 → 视为「妈妈自拍」 */
    private static final float MOM_AGE_MIN = 40f;
    /** 年龄 ≤ 此值 → 视为「小孩自拍」 */
    private static final float CHILD_AGE_MAX = 14f;

    public interface DecideCallback {
        void onDecided(boolean shouldRemove);
    }

    private final FaceDetector detector;
    private final AgeGenderEstimator ageGender;

    public AvatarClassifier(Context context) {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.25f)
                .build();
        detector = FaceDetection.getClient(options);
        ageGender = new AgeGenderEstimator(context);
        Log.i(TAG, "分类器就绪，年龄模型=" + ageGender.isReady());
    }

    public boolean isAgeModelReady() {
        return ageGender.isReady();
    }

    /** 判定一张头像是否应被移除 */
    public void decide(Bitmap avatar, final DecideCallback cb) {
        if (avatar == null || avatar.isRecycled()) {
            cb.onDecided(false); // 拿不到图就不动，保守
            return;
        }
        InputImage image = InputImage.fromBitmap(avatar, 0);
        detector.process(image)
                .addOnSuccessListener(faces -> decideByFaces(avatar, faces, cb))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "人脸检测失败，按「保留」处理", e);
                    cb.onDecided(false);
                });
    }

    private void decideByFaces(Bitmap avatar, @NonNull List<Face> faces, DecideCallback cb) {
        if (faces.isEmpty()) {
            // 无人脸 → 风景/书法/作品/默认头像
            cb.onDecided(true);
            return;
        }
        if (!ageGender.isReady()) {
            Log.w(TAG, "年龄模型未加载，保留该头像");
            cb.onDecided(false);
            return;
        }

        // 取面积最大的人脸
        Face biggest = faces.get(0);
        for (Face f : faces) {
            if (area(f.getBoundingBox()) > area(biggest.getBoundingBox())) {
                biggest = f;
            }
        }

        Bitmap faceCrop = cropFace(avatar, biggest.getBoundingBox());
        if (faceCrop == null) {
            cb.onDecided(false);
            return;
        }

        AgeGenderEstimator.Result r = ageGender.estimate(faceCrop);
        if (faceCrop != avatar) {
            faceCrop.recycle();
        }
        if (r == null) {
            cb.onDecided(false);
            return;
        }

        boolean mom = r.isFemale && r.age >= MOM_AGE_MIN;
        boolean child = r.age <= CHILD_AGE_MAX;
        Log.d(TAG, "年龄=" + r.age + " 女性=" + r.isFemale
                + " → " + (mom ? "妈妈自拍" : child ? "小孩自拍" : "保留"));
        cb.onDecided(mom || child);
    }

    private int area(Rect r) {
        return r.width() * r.height();
    }

    /** 按人脸框裁一个正方形区域（外扩 40%），夹到图像边界内 */
    private Bitmap cropFace(Bitmap src, Rect box) {
        int w = src.getWidth();
        int h = src.getHeight();
        int cx = box.centerX();
        int cy = box.centerY();
        int side = (int) (Math.max(box.width(), box.height()) * 1.4f);
        side = Math.max(side, 32);
        int left = Math.max(0, cx - side / 2);
        int top = Math.max(0, cy - side / 2);
        int right = Math.min(w, left + side);
        int bottom = Math.min(h, top + side);
        if (right - left < 8 || bottom - top < 8) {
            return null;
        }
        try {
            return Bitmap.createBitmap(src, left, top, right - left, bottom - top);
        } catch (Exception e) {
            Log.w(TAG, "裁剪人脸失败", e);
            return null;
        }
    }

    public void close() {
        try {
            detector.close();
        } catch (Exception ignored) {
        }
        ageGender.close();
    }
}
