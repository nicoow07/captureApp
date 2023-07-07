# Current workflow
mTextureView.SurfaceTextureListener -> onSurfaceTextureAvailable -> openCamera()

openCamera() -> mCamManager.openCamera -> mCameraOpenCallback -> onOpened()

RefreshBtn.click -> fetchCameraParams -> startPreview() + setupCameraShotRequest()

startPreview() -> mCurrentCameraParamsMap.get("output_size");
	mPreviewCaptureBuilder = mCameraDevice.createCaptureRequest
	mPreviewCaptureBuilder.addTarget

	mCameraDevice.createCaptureSession -> onConfigured() -> updatePreview()

setupCameraShotRequest() -> mShotCaptureBuilder = mCameraDevice.createCaptureRequest
