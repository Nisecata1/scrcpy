package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.device.Point;
import com.genymobile.scrcpy.device.Position;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.util.Ln;

public final class PositionMapper {

    private final Size videoSize;
    private final AffineMatrix videoToDeviceMatrix;

    public PositionMapper(Size videoSize, AffineMatrix videoToDeviceMatrix) {
        this.videoSize = videoSize;
        this.videoToDeviceMatrix = videoToDeviceMatrix;
    }

    public static PositionMapper create(Size videoSize, AffineMatrix filterTransform, Size targetSize) {
        boolean convertToPixels = !videoSize.equals(targetSize) || filterTransform != null;
        AffineMatrix transform = filterTransform;
        if (convertToPixels) {
            AffineMatrix inputTransform = AffineMatrix.ndcFromPixels(videoSize);
            AffineMatrix outputTransform = AffineMatrix.ndcToPixels(targetSize);
            transform = outputTransform.multiply(transform).multiply(inputTransform);
        }

        return new PositionMapper(videoSize, transform);
    }

    public Size getVideoSize() {
        return videoSize;
    }

    public Point map(Position position) {
        Size clientVideoSize = position.getScreenSize();
        Point point = position.getPoint();

        if (!videoSize.equals(clientVideoSize)) {
            int clientWidth = clientVideoSize.getWidth();
            int clientHeight = clientVideoSize.getHeight();
            if (clientWidth <= 1 || clientHeight <= 1) {
                Ln.w("Ignore positional event with invalid client size: " + clientVideoSize);
                return null;
            }

            int videoWidth = videoSize.getWidth();
            int videoHeight = videoSize.getHeight();
            if (videoWidth <= 1 || videoHeight <= 1) {
                Ln.w("Ignore positional event with invalid video size: " + videoSize);
                return null;
            }

            float nx = (float) point.getX() / (clientWidth - 1);
            float ny = (float) point.getY() / (clientHeight - 1);
            nx = Math.max(0f, Math.min(1f, nx));
            ny = Math.max(0f, Math.min(1f, ny));

            int vx = Math.round(nx * (videoWidth - 1));
            int vy = Math.round(ny * (videoHeight - 1));
            point = new Point(vx, vy);
        }

        if (videoToDeviceMatrix != null) {
            point = videoToDeviceMatrix.apply(point);
        }
        return point;
    }
}
