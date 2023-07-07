package com.example.camera_params;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.icu.util.Calendar;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MainActivity extends Activity {
    public static final String CAMERA_SHUTTER_SPEED = "";// matches with 1/4000
    public static final String CAMERA_ISO = "";//
    public static final String CAMERA_FOCUS = "";//
    private static final int CAMERA_REQUEST_CODE = 234;
    private Button mRefreshBtn;
    private Button mCaptureBtn;
    private TextView mShutterSpeedTextView;
    private TextView mFocusTextView;
    private TextView mIsoTextView;
    private TextView mWbBalanceTextView;
    private TextView mcaptureRateTextView;
    private TextView moutputSizeTextView;
    private TextView moutputRotationTextView;
    private TextureView mTextureView;
    private ArrayMap<String, String> mCurrentCameraParamsMap;
    //===CAMERA variables====
    private CameraManager mCamManager;
    private CameraCharacteristics mCamCharacteristics;
    private String mBackCamId;
    private Size mPreviewSize;
    private CameraDevice.StateCallback mCameraOpenCallback;
    private CameraDevice mCameraDevice;
    // Preview
    private CaptureRequest.Builder mPreviewCaptureBuilder;
    private CameraCaptureSession mPreviewSession;
    //Capture
    private CaptureRequest.Builder mShotCaptureBuilder;
    private ImageReader mImageReader;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //INIT VARIABLES
        mCurrentCameraParamsMap = new ArrayMap<String, String>();
        //Retrieve UI widgets
        mShutterSpeedTextView = (TextView) findViewById(R.id.shutterSpeedTextView);
        mFocusTextView = (TextView) findViewById(R.id.focusTextView);
        mIsoTextView = (TextView) findViewById(R.id.isoTextView);
        mWbBalanceTextView = (TextView) findViewById(R.id.wbBalanceTextView);
        mcaptureRateTextView = (TextView) findViewById(R.id.captureRateTextView);
        moutputSizeTextView = (TextView) findViewById(R.id.outputSizeTextView);
        moutputRotationTextView = (TextView) findViewById(R.id.outputRotationTextView);
        mTextureView = (TextureView) findViewById(R.id.textureView);
        //Create the surface listener which will trigger openCamera() when the surface is ready to be used
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                Log.i("Surface texture", "onSurfaceTextureAvailable");
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                Log.i("Surface texture", "onSurfaceTextureSizeChanged");
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                Log.i("Surface texture", "onSurfaceTextureDestroyed");
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
                //Called every time the surface is updated
            }
        });

        //Set refresh button callback
        mRefreshBtn = (Button) findViewById(R.id.refreshBtn);
        mRefreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new fetchCameraParams().start();
            }
        });

        //Set capture btn callback
        mCaptureBtn = (Button) findViewById(R.id.captureBtn);
        mCaptureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takeShot();
            }
        });

        mPreviewSize = new Size(768, 1024);
        mCameraOpenCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Log.i(TAG, "on camera opened");
                mCameraDevice = camera;
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Log.e(TAG, "onDisconnected");
                camera.close();
                mCameraDevice = null;
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.e(TAG, "onError");
                camera.close();
                mCameraDevice = null;
            }
        };

        try {
            //Initialize Camera manager, once and for all
            mCamManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);

            //Get the camera ID for the back (not selfie) camera
            mBackCamId = getBackFacingCameraID();
            Log.i("Back facing camera ID", mBackCamId);

            //Retrieve the characteristics of the back facing camera
            mCamCharacteristics = mCamManager.getCameraCharacteristics(mBackCamId);
            //Print in log the supported picture format
            logOutputSizes();
            //Print the available auto exposure modes
            logAutoExposureAvailableModes();

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot get camera characteristics");
            throw new RuntimeException();
        }


        //Check the camera supports manual settings change
        Boolean camManualParamSettingAvailable = null;
        try {
            camManualParamSettingAvailable = checkCamManualParamSettingAllowed(mBackCamId);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot check if camera supports Manual_Sensor");
            throw new RuntimeException();
        }

        //If camera supports manual sensor, open it
        if (camManualParamSettingAvailable) {
            Log.i("MANUAL SENSOR", "SUPPORTED");
        } else {
            Log.i("MANUAL SENSOR", "NOT SUPPORTED");
        }

        //If camera supports manual sensor, set camera params with fetched params from server.


    }

    //Print in log.i the output sizes supported under JPG format of the camera ID passed in argument
    public void logOutputSizes() {
        StreamConfigurationMap configs = mCamCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        //Display output sizes (under jpg format)
        Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null) {
            Log.i("---Picture sizes---", "JPG format not supported");
        } else {
            for (Size size : sizes) {
                Log.i("---Picture sizes---", size.toString());
            }
        }
    }

    //Print in log.i the auto exposure modes available on the camera
    public void logAutoExposureAvailableModes() {
        final int[] availableAeModes = mCamCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        for(int mode : availableAeModes){
            Log.d("AE mode :", String.valueOf(mode));
        }
    }

    // Function called by class fetchCameraParams when the data are retrieved from server
    public void displayCameraParams(JSONObject jsonObj) {
        Log.i("FETCHED CAMERA PARAMS", jsonObj.toString());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mShutterSpeedTextView.setText("Shutter speed: " + "1/" + mCurrentCameraParamsMap.get("shutter_speed"));
                mFocusTextView.setText("Focus: " + mCurrentCameraParamsMap.get("focus"));
                mIsoTextView.setText("ISO: " + mCurrentCameraParamsMap.get("iso"));
                mWbBalanceTextView.setText("W&B balance: " + mCurrentCameraParamsMap.get("wb_balance"));
                mcaptureRateTextView.setText("Capture / sec: " + mCurrentCameraParamsMap.get("capture_per_sec"));
                moutputSizeTextView.setText("Capture format: " + mCurrentCameraParamsMap.get("output_size"));
                moutputRotationTextView.setText("Image rotation: " + mCurrentCameraParamsMap.get("output_rotation"));
            }
        });


    }

    public String getBackFacingCameraID() throws CameraAccessException {
        // Get the camera manager

        //Get the camera ID for the back (not selfie) camera
        String[] cameraIds = mCamManager.getCameraIdList();
        Log.i("----Number of camera available on device---- ", String.valueOf(cameraIds.length));
        String backCameraID = "";
        for (String camId : cameraIds) {
            CameraCharacteristics characteristics = mCamManager.getCameraCharacteristics(camId);
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                backCameraID = camId;
            }
        }
        return backCameraID;
    }

    //Check if the params Focus, shutter speed, ISO and W&B balance can be manually changed.
    // Returns true if camera has MANUAL_SENSOR capability
    public Boolean checkCamManualParamSettingAllowed(String camId) throws CameraAccessException {
        Boolean allParamsChangeable = true;
        //Get camera supported capabilities
        int[] capabilities = mCamCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);

        //Check if camera has Manual sensor capability
        Boolean cameraHasManualSensorCapability = false;
        for (int capability : capabilities) {
            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                cameraHasManualSensorCapability = true;
            }
        }

        return cameraHasManualSensorCapability;
    }

    //========CAMERA 2 API HANDLING==============

    public void openCamera() {
        Log.i(TAG, "Opening camera...");
        try {
            StreamConfigurationMap map = mCamCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Log.i(TAG, "Selected preview size is: " + mPreviewSize.toString());

            //Runtime camera permission request
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
                Log.i("----Permissions---- ", "Requesting camera permission at run time");
            }
            mCamManager.openCamera(mBackCamId, mCameraOpenCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException when opening the camera");
            e.printStackTrace();
        }
    }

    public void startPreview(){
        Log.i(TAG,"Starting preview");
        //Check cameraDevice object is working
        if(mCameraDevice == null){
            Log.e(TAG, "startPreview fail, Camera Device == null");
            return;
        } else if (!mTextureView.isAvailable()) {
            Log.e(TAG, "startPreview fail, Texture view is not available");
            return;
        }

        //Get the surfaceTexture to display the shots
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if(null == texture) {
            Log.e(TAG,"texture is null, return");
            return;
        }

        //Initialise texture & surface for preview
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(texture);

        //Initialize Image reader to handle shot captures
        //Get the output size
        String outpuSizeStr = mCurrentCameraParamsMap.get("output_size");
        Size outputCaptureSize = Size.parseSize(outpuSizeStr);
        Log.i(TAG, "Output size: " + outpuSizeStr);
        mImageReader = ImageReader.newInstance(outputCaptureSize.getWidth(), outputCaptureSize.getHeight(), ImageFormat.JPEG, /*maxImages*/2);

        //Set the saving image to file process when an image is available in the imgReader (when a picture has been shot)
        //Create a thread to send capture request in background.
        HandlerThread thread = new HandlerThread("ShotCapture");
        thread.start();
        Handler backgroundHandler = new Handler(thread.getLooper());
        //Create the listener to be called when an image is available in the imageReader
        ImageReader.OnImageAvailableListener saveImageToFileListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.i(TAG, "IMAGE AVAILABLE");
                // TODO Rotate image
                //Retrieve the image taken from the shot. Get the byte[] associated to it in order to store the image
                Image capture = imageReader.acquireLatestImage();
                Image.Plane[] capturePlane = capture.getPlanes();
                //Let's try first by storing only the [0] Plane
                ByteBuffer captureBuffer = capturePlane[0].getBuffer();
                byte[] captureBytes = new byte[captureBuffer.remaining()];
                captureBuffer.get(captureBytes);

                //Create the file to save the image to.
                File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                String imgFileName = String.valueOf(Calendar.getInstance().getTimeInMillis()) + "_shot.jpg";
                File file = new File(path, imgFileName);

                //Rotate the image
                Bitmap bitmapShot = BitmapFactory.decodeByteArray(captureBytes, 0, captureBytes.length);
                Matrix m = new Matrix();
                m.postRotate(Integer.parseInt(Objects.requireNonNull(mCurrentCameraParamsMap.get("output_rotation"))));
                bitmapShot = Bitmap.createBitmap(bitmapShot, 0, 0, bitmapShot.getWidth(), bitmapShot.getHeight(), m, true);

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bitmapShot.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                byte[] rotatedCaptureBytes = bos.toByteArray();

                try {
                    // Make sure the Pictures directory exists.
                    path.mkdirs();

                    //Write the capture byte[] into the file
                    OutputStream os = new FileOutputStream(file);
                    os.write(rotatedCaptureBytes);
                    os.close();
                    capture.close();

                    // Tell the media scanner about the new file so that it is immediately available to the user.
                    MediaScannerConnection.scanFile(getApplicationContext(), new String[] { file.toString() }, null, new MediaScannerConnection.OnScanCompletedListener() {
                                public void onScanCompleted(String path, Uri uri) {
                                    Log.i("ExternalStorage", "Scanned " + path + ":");
                                    Log.i("ExternalStorage", "-> uri=" + uri);
                                }
                            });
                } catch (IOException e) {
                    // Unable to create file, likely because external storage is not currently mounted.
                    Log.w("ExternalStorage", "Error writing " + file, e);
                }
            }
        };
        mImageReader.setOnImageAvailableListener(saveImageToFileListener, backgroundHandler);


        //Get a capture request
        try {
            mPreviewCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        //Connect the camera preview to the surface
        mPreviewCaptureBuilder.addTarget(previewSurface);

        try {
            //Creating capture session with both textureview and imageReader as output
            List targetOutputs = Arrays.asList(previewSurface, mImageReader.getSurface());
            //Create a capture session for the preview. (it holds the parameters we want to set for the camera)
            mCameraDevice.createCaptureSession(targetOutputs, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.i(TAG, "PREVIEW CAPTURE SESSION READY");
                    mPreviewSession = session;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG,"onConfiguration failed.");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void updatePreview(){
        Log.i(TAG, "updatePreview()");
        if(mCameraDevice == null) {
            Log.e(TAG, "updatePreview error, return");
            return;
        }
        if(mPreviewCaptureBuilder == null){
            Log.i(TAG, "mPreviewCaptureBuilder not ready yet to updatePreview");
            return;
        }

        //Enable auto-exposure + auto w-b balance + auto focus
        //mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);
        //Disable auto Exposure
        mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        //Set ISO level according to the camera params stored in mCurrentCameraParamsMap
        //Log.i(TAG, "Setting ISO level to: " + mCurrentCameraParamsMap.get("iso"));
        //mPreviewCaptureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, Integer.valueOf(mCurrentCameraParamsMap.get("iso")));


        //Create a thread to send capture request in loop.
        HandlerThread thread = new HandlerThread("CameraPreview");
        thread.start();
        Handler backgroundHandler = new Handler(thread.getLooper());

        try {
            mPreviewSession.setRepeatingRequest(mPreviewCaptureBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void setupCameraShotRequest(){
        Log.i(TAG,"setupCameraShotRequest");
        if(mCameraDevice == null){
            Log.e(TAG, "mCameraDevice is NULL");
            return;
        }
        //Create a capture request for shots taking
        try {
            mShotCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        //Connect the camera preview to the surface
        mShotCaptureBuilder.addTarget(mImageReader.getSurface());

    }

    public void takeShot(){
        Log.i(TAG, "takeShot()");
        if(mCameraDevice == null | mShotCaptureBuilder == null) {
            Log.e(TAG, "takeShot error: camera is null or capture builder");
            return;
        }
/*
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED);
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
       */

        //Create a thread to send capture request in loop.
        HandlerThread thread = new HandlerThread("ShotCapture");
        thread.start();
        Handler backgroundHandler = new Handler(thread.getLooper());

        try {
            mPreviewSession.capture(mShotCaptureBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //Class to retrieve JSON file with camera parameters from remote server to ease config without rebuilding the app, and without building UI
    //Whenever the JSON file is retrieved from the server, the preview is updated with the new parameters. updatePreview() is called.
    public class fetchCameraParams extends Thread{
        private static final String fileURLStr = "http://192.168.0.105:8080/camera_parameters.json";// Home IP
        //private static final String fileURLStr = "http://192.168.8.103:8080/camera_parameters.json";// Work IP
        String data = "";
        JSONObject jsonFile = null;

        @Override
        public void run() {
            try{
                //Init jsonFile with dummy values
                jsonFile = new JSONObject();
                jsonFile.put("shutter_speed", "1/?");
                jsonFile.put("focus", "?");
                jsonFile.put("iso", "?");
                jsonFile.put("wb_balance", "?");
                jsonFile.put("capture_per_sec", "0");
                jsonFile.put("output_size", "720x480");
                jsonFile.put("output_rotation", "90");

                URL url = new URL(fileURLStr);
                HttpURLConnection httpUrlCnx = (HttpURLConnection) url.openConnection();
                InputStream inputstream = httpUrlCnx.getInputStream();
                BufferedReader buffReader = new BufferedReader(new InputStreamReader(inputstream));
                String line;
                while( (line = buffReader.readLine()) != null){
                    data = data + line;
                }

                if(!data.isEmpty()){
                    jsonFile = new JSONObject(data);

                    //Extract data from JSON to array map
                    try {
                        mCurrentCameraParamsMap.put("shutter_speed", jsonFile.getString("shutter_speed"));
                        mCurrentCameraParamsMap.put("focus", jsonFile.getString("focus"));
                        mCurrentCameraParamsMap.put("iso", jsonFile.getString("iso"));
                        mCurrentCameraParamsMap.put("wb_balance", jsonFile.getString("wb_balance"));
                        mCurrentCameraParamsMap.put("capture_per_sec", jsonFile.getString("capture_per_sec"));
                        mCurrentCameraParamsMap.put("output_size", jsonFile.getString("output_size"));
                        mCurrentCameraParamsMap.put("output_rotation", jsonFile.getString("output_rotation"));
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }

                    //This is purely random. If startpreview (and createCaptureSession) is not called from UI thread, it throw error "IllegalArgumentException: No handler given, and current thread has no looper!"
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            //Start preview
                            startPreview();
                            //Set capture params
                            setupCameraShotRequest();
                        }
                    });


                    //If data are successfully fetched form file, update the preview with the new camera params.
                    //updatePreview();
                }
                else{
                    Log.e(TAG, "JSON FILE IS EMPTY");
                }
            } catch (MalformedURLException e) {
                Log.e(TAG, "MalformedURLException thrown when retrieving JSON file with camera params");
                //throw new RuntimeException(e);
            } catch (JSONException e) {
                Log.e(TAG, "MalformedURLException thrown when retrieving JSON file with camera params");
                //throw new RuntimeException(e);
            } catch (IOException e) {
                Log.e(TAG, "MalformedURLException thrown when retrieving JSON file with camera params");
                //throw new RuntimeException(e);
            }
            finally {
                MainActivity.this.displayCameraParams(jsonFile);
            }
        }
    }
}

