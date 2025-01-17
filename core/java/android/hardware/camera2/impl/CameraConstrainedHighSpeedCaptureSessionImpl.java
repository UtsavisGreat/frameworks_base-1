/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.hardware.camera2.impl;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.HighSpeedVideoConfiguration;
import android.hardware.camera2.utils.SurfaceUtils;
import android.os.Handler;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

import static com.android.internal.util.Preconditions.*;

/**
 * Standard implementation of CameraConstrainedHighSpeedCaptureSession.
 *
 * <p>
 * Mostly just forwards calls to an instance of CameraCaptureSessionImpl,
 * but implements the few necessary behavior changes and additional methods required
 * for the constrained high speed speed mode.
 * </p>
 */

public class CameraConstrainedHighSpeedCaptureSessionImpl
        extends CameraConstrainedHighSpeedCaptureSession implements CameraCaptureSessionCore {
    private final CameraCharacteristics mCharacteristics;
    private final CameraCaptureSessionImpl mSessionImpl;

    /**
     * Create a new CameraCaptureSession.
     *
     * <p>The camera device must already be in the {@code IDLE} state when this is invoked.
     * There must be no pending actions
     * (e.g. no pending captures, no repeating requests, no flush).</p>
     */
    CameraConstrainedHighSpeedCaptureSessionImpl(int id,
            CameraCaptureSession.StateCallback callback, Executor stateExecutor,
            android.hardware.camera2.impl.CameraDeviceImpl deviceImpl,
            Executor deviceStateExecutor, boolean configureSuccess,
            CameraCharacteristics characteristics) {
        mCharacteristics = characteristics;
        CameraCaptureSession.StateCallback wrapperCallback = new WrapperCallback(callback);
        mSessionImpl = new CameraCaptureSessionImpl(id, /*input*/null, wrapperCallback,
                stateExecutor, deviceImpl, deviceStateExecutor, configureSuccess);
    }

    @Override
    public List<CaptureRequest> createHighSpeedRequestList(CaptureRequest request)
            throws CameraAccessException {
        if (request == null) {
            throw new IllegalArgumentException("Input capture request must not be null");
        }
        Collection<Surface> outputSurfaces = request.getTargets();
        Range<Integer> fpsRange = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);

        StreamConfigurationMap config =
                mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        SurfaceUtils.checkConstrainedHighSpeedSurfaces(outputSurfaces, fpsRange, config);

        int requestListSize = getHighSpeedRequestListSize(fpsRange, outputSurfaces);
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();

        // Prepare the Request builders: need carry over the request controls.
        // First, create a request builder that will only include preview or recording target.
        CameraMetadataNative requestMetadata = new CameraMetadataNative(request.getNativeCopy());
        // Note that after this step, the requestMetadata is mutated (swapped) and can not be used
        // for next request builder creation.
        CaptureRequest.Builder singleTargetRequestBuilder = new CaptureRequest.Builder(
                requestMetadata, /*reprocess*/false, CameraCaptureSession.SESSION_ID_NONE,
                request.getLogicalCameraId(), /*physicalCameraIdSet*/ null);
        // Carry over userTag, as native metadata doesn't have this field.
        singleTargetRequestBuilder.setTag(request.getTag());

        // Overwrite the capture intent to make sure a good value is set.
        Iterator<Surface> iterator = outputSurfaces.iterator();
        Surface firstSurface = iterator.next();
        Surface secondSurface = null;
        if (outputSurfaces.size() == 1 && SurfaceUtils.isSurfaceForHwVideoEncoder(firstSurface)) {
            singleTargetRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
        } else {
            // Video only, or preview + video
            singleTargetRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
        }
        singleTargetRequestBuilder.setPartOfCHSRequestList(/*partOfCHSList*/true);

        // Second, Create a request builder that will include both preview and recording targets.
        CaptureRequest.Builder doubleTargetRequestBuilder = null;
        if (outputSurfaces.size() == 2) {
            // Have to create a new copy, the original one was mutated after a new
            // CaptureRequest.Builder creation.
            requestMetadata = new CameraMetadataNative(request.getNativeCopy());
            doubleTargetRequestBuilder = new CaptureRequest.Builder(
                    requestMetadata, /*reprocess*/false, CameraCaptureSession.SESSION_ID_NONE,
                    request.getLogicalCameraId(), /*physicalCameraIdSet*/null);
            doubleTargetRequestBuilder.setTag(request.getTag());
            doubleTargetRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
            doubleTargetRequestBuilder.addTarget(firstSurface);
            secondSurface = iterator.next();
            doubleTargetRequestBuilder.addTarget(secondSurface);
            doubleTargetRequestBuilder.setPartOfCHSRequestList(/*partOfCHSList*/true);
            // Make sure singleTargetRequestBuilder contains only recording surface for
            // preview + recording case.
            Surface recordingSurface = firstSurface;
            if (!SurfaceUtils.isSurfaceForHwVideoEncoder(recordingSurface)) {
                recordingSurface = secondSurface;
            }
            singleTargetRequestBuilder.addTarget(recordingSurface);
        } else {
            // Single output case: either recording or preview.
            singleTargetRequestBuilder.addTarget(firstSurface);
        }

        // Generate the final request list.
        for (int i = 0; i < requestListSize; i++) {
            if (i == 0 && doubleTargetRequestBuilder != null) {
                // First request should be recording + preview request
                requestList.add(doubleTargetRequestBuilder.build());
            } else {
                requestList.add(singleTargetRequestBuilder.build());
            }
        }

        return Collections.unmodifiableList(requestList);
    }

    private boolean isConstrainedHighSpeedRequestList(List<CaptureRequest> requestList) {
        checkCollectionNotEmpty(requestList, "High speed request list");
        for (CaptureRequest request : requestList) {
            if (!request.isPartOfCRequestList()) {
                return false;
            }
        }
        return true;
    }

    private int getHighSpeedRequestListSize(Range<Integer> fpsRange, Collection<Surface> surfaces) {
        int requestListSize = 0;

        for (Surface surface : surfaces) {

            if (SurfaceUtils.isSurfaceForHwVideoEncoder(surface)) {
                Size surfaceSize = SurfaceUtils.getSurfaceSize(surface);
                HighSpeedVideoConfiguration[] highSpeedVideoConfigurations =
                    mCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS);

                // Get the batchsize for matching FPS & video size
                for (HighSpeedVideoConfiguration config : highSpeedVideoConfigurations) {
                    if (config.getSize().equals(surfaceSize) && config.getFpsRange().equals(fpsRange)) {
                        requestListSize = config.getBatchSizeMax();
                        break;
                     }
                }
                break;
            }
        }

        if (requestListSize == 0) {
            // If cant' find the matching batch size,  limit the preview to 30fps.
            requestListSize = fpsRange.getUpper() / 30;
        }
        return requestListSize;
    }

    @Override
    public CameraDevice getDevice() {
        return mSessionImpl.getDevice();
    }

    @Override
    public void prepare(Surface surface) throws CameraAccessException {
        mSessionImpl.prepare(surface);
    }

    @Override
    public void prepare(int maxCount, Surface surface) throws CameraAccessException {
        mSessionImpl.prepare(maxCount, surface);
    }

    @Override
    public void tearDown(Surface surface) throws CameraAccessException {
        mSessionImpl.tearDown(surface);
    }

    @Override
    public int capture(CaptureRequest request, CaptureCallback listener, Handler handler)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Constrained high speed session doesn't support"
                + " this method");
    }

    @Override
    public int captureSingleRequest(CaptureRequest request, Executor executor,
            CaptureCallback listener) throws CameraAccessException {
        throw new UnsupportedOperationException("Constrained high speed session doesn't support"
                + " this method");
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback listener,
            Handler handler) throws CameraAccessException {
        if (!isConstrainedHighSpeedRequestList(requests)) {
            throw new IllegalArgumentException(
                "Only request lists created by createHighSpeedRequestList() can be submitted to " +
                "a constrained high speed capture session");
        }
        return mSessionImpl.captureBurst(requests, listener, handler);
    }

    @Override
    public int captureBurstRequests(List<CaptureRequest> requests, Executor executor,
            CaptureCallback listener) throws CameraAccessException {
        if (!isConstrainedHighSpeedRequestList(requests)) {
            throw new IllegalArgumentException(
                "Only request lists created by createHighSpeedRequestList() can be submitted to " +
                "a constrained high speed capture session");
        }
        return mSessionImpl.captureBurstRequests(requests, executor, listener);
    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback listener,
            Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Constrained high speed session doesn't support"
                + " this method");
    }

    @Override
    public int setSingleRepeatingRequest(CaptureRequest request, Executor executor,
            CaptureCallback listener) throws CameraAccessException {
        throw new UnsupportedOperationException("Constrained high speed session doesn't support"
                + " this method");
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener,
            Handler handler) throws CameraAccessException {
        if (!isConstrainedHighSpeedRequestList(requests)) {
            throw new IllegalArgumentException(
                "Only request lists created by createHighSpeedRequestList() can be submitted to " +
                "a constrained high speed capture session");
        }
        return mSessionImpl.setRepeatingBurst(requests, listener, handler);
    }

    @Override
    public int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor,
            CaptureCallback listener) throws CameraAccessException {
        if (!isConstrainedHighSpeedRequestList(requests)) {
            throw new IllegalArgumentException(
                "Only request lists created by createHighSpeedRequestList() can be submitted to " +
                "a constrained high speed capture session");
        }
        return mSessionImpl.setRepeatingBurstRequests(requests, executor, listener);
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        mSessionImpl.stopRepeating();
    }

    @Override
    public void abortCaptures() throws CameraAccessException {
        mSessionImpl.abortCaptures();
    }

    @Override
    public Surface getInputSurface() {
        return null;
    }

    @Override
    public void updateOutputConfiguration(OutputConfiguration config)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Constrained high speed session doesn't support"
                + " this method");
    }

    @Override
    public void close() {
        mSessionImpl.close();
    }

    @Override
    public boolean isReprocessable() {
        return false;
    }

    // Implementation of CameraCaptureSessionCore methods

    @Override
    public void replaceSessionClose() {
        mSessionImpl.replaceSessionClose();
    }

    @Override
    public CameraDeviceImpl.StateCallbackKK getDeviceStateCallback() {
        return mSessionImpl.getDeviceStateCallback();
    }

    @Override
    public boolean isAborting() {
        return mSessionImpl.isAborting();
    }

    @Override
    public void finalizeOutputConfigurations(List<OutputConfiguration> deferredOutputConfigs)
            throws CameraAccessException {
        mSessionImpl.finalizeOutputConfigurations(deferredOutputConfigs);
    }

    private class WrapperCallback extends StateCallback {
        private final StateCallback mCallback;

        public WrapperCallback(StateCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            mCallback.onConfigured(CameraConstrainedHighSpeedCaptureSessionImpl.this);
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            mCallback.onConfigureFailed(CameraConstrainedHighSpeedCaptureSessionImpl.this);
        }

        @Override
        public void onReady(CameraCaptureSession session) {
            mCallback.onReady(CameraConstrainedHighSpeedCaptureSessionImpl.this);
        }

        @Override
        public void onActive(CameraCaptureSession session) {
            mCallback.onActive(CameraConstrainedHighSpeedCaptureSessionImpl.this);
        }

        @Override
        public void onCaptureQueueEmpty(CameraCaptureSession session) {
            mCallback.onCaptureQueueEmpty(CameraConstrainedHighSpeedCaptureSessionImpl.this);
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            mCallback.onClosed(CameraConstrainedHighSpeedCaptureSessionImpl.this);
        }

        @Override
        public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {
            mCallback.onSurfacePrepared(CameraConstrainedHighSpeedCaptureSessionImpl.this,
                    surface);
        }
    }
}
