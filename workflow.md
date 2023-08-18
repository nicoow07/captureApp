# Current workflow

onStart -> openCamera() -> mCamManager.openCamera -> mCameraOpenCallback -> onOpened()
-> if mCurrentCameraParamsMap is not Empty
		startPreview() -> setupCameraShotRequest();

startPreview -> if mCameraDevice == null
					openCamera()
				if not texture.isAvailable
					mWaitingForTextureToStartPreview = true

mTextureView.SurfaceTextureListener -> onSurfaceTextureAvailable -> 
	if mWaitingForTextureToStartPreview
		startPreview

# Previous workflow
mTextureView.SurfaceTextureListener -> onSurfaceTextureAvailable -> openCamera()

openCamera() -> mCamManager.openCamera -> mCameraOpenCallback -> onOpened()

RefreshBtn.click -> fetchCameraParams -> startPreview() + setupCameraShotRequest()

startPreview() -> mCurrentCameraParamsMap.get("output_size");
	mPreviewCaptureBuilder = mCameraDevice.createCaptureRequest
	mPreviewCaptureBuilder.addTarget

	mCameraDevice.createCaptureSession -> onConfigured() -> updatePreview()

setupCameraShotRequest() -> mShotCaptureBuilder = mCameraDevice.createCaptureRequest
