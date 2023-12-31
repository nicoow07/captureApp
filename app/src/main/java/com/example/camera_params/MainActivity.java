package com.example.camera_params;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatToggleButton;
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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.icu.util.Calendar;
import android.icu.util.Output;
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
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MainActivity extends Activity {
    public static final String CAMERA_SHUTTER_SPEED = "";// matches with 1/4000
    public static final String CAMERA_ISO = "";//
    public static final String CAMERA_FOCUS = "";//
    private static final int CAMERA_REQUEST_CODE = 234;
    private Button mRefreshBtn;
    private ToggleButton mCaptureBtn;
    private TextView mShutterSpeedTextView;
    private TextView mFocusTextView;
    private TextView mIsoTextView;
    private TextView mWbBalanceTextView;
    private TextView mcaptureRateTextView;
    private TextView moutputSizeTextView;
    private TextView moutputRotationTextView;
    private TextureView mTextureView;
    private ArrayMap<String, String> mCurrentCameraParamsMap;
    private StreamConfigurationMap mStreamConfigMap;
    //===CAMERA variables====
    private CameraManager mCamManager;
    private CameraCharacteristics mCamCharacteristics;
    private String mBackCamId;
    private Size mPreviewSize;
    private Boolean mWaitingTextureToStartPreview;
    private CameraDevice.StateCallback mCameraOpenCallback;
    private CameraDevice mCameraDevice;
    // Preview
    private CaptureRequest.Builder mPreviewCaptureBuilder;
    private CameraCaptureSession mCaptureSession;
    //Capture
    private CaptureRequest.Builder mShotCaptureBuilder;
    private ImageReader mImageReader;
    private Thread mCustomRepeatCaptureThread;
    private Boolean mRepeatCaptureRunning;//True = repeat captures running. False = stop captures.
    //Attributes related to Web server to send images
    private OutputStream mImageOutputStream;
    private HttpURLConnection mImageServerConnection;
    private int mCaptureSessionCount;// Count to send to the analysis script.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //INIT VARIABLES
        mCaptureSessionCount = 0;
        mWaitingTextureToStartPreview = false;
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
                // If the preview is the last blocking element to start preview, start it.
                if(mWaitingTextureToStartPreview){
                    startPreview();
                }
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

        //Load saved camera config + start preview
        loadCamConfigFromFile();

        mImageServerConnection = null;
        mImageOutputStream = null;

        //Set refresh button callback
        mRefreshBtn = (Button) findViewById(R.id.refreshBtn);
        mRefreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new fetchCameraParams().start();
            }
        });

        //Set capture btn callback
        mCaptureBtn = (ToggleButton) findViewById(R.id.captureBtn);
        mCaptureBtn.setEnabled(false);//Disable button until camera parameters have been fetched from file on server
        mCaptureBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                //b == True means to start capturing
                //TODO add repeated capture request
                Log.i("TOGGLE BUTTON CHANGED", Boolean.toString(b));

                if(b == true){
                    //Start a new capture session: increase its count
                    mCaptureSessionCount = mCaptureSessionCount + 1;
                    //Send capture session ID to img processing server. Indicating we are starting a capture session.
                    //And then start the custom repeat capture thread
                    new startNewCaptureSession().start();
                }else{
                    //Send close capture session request to img proc server, and stop custom repeat capture thread.
                    new closeCaptureSession().start();
                }
            }
        });

        mPreviewSize = new Size(768, 1024);
        mCameraOpenCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Log.i(TAG, "CAMERA OPENED");
                mCameraDevice = camera;

                //If parameters already loaded from file or from webserver, start preview
                if(!mCurrentCameraParamsMap.isEmpty()){
                    //Start preview
                    startPreview();

                    //Enable capture button
                    mCaptureBtn.setEnabled(true);
                }
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

        //Ask runtime storage permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            Log.i("----Permissions---- ", "Requesting write storage permission at run time");
        }if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
            Log.i("----Permissions---- ", "Requesting read storage permission at run time");
        }

        //Create thread for repeat captures. The thread will run as long as the app is running. The global variable mRepeatCaptureThreadState is read by the thread to manage it's behavior
        mRepeatCaptureRunning = false;//Initially the captures are not taken
        mCustomRepeatCaptureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int sleepTime = 1000;
                //Create a thread to send capture request in loop.
                HandlerThread thread = new HandlerThread("ShotCapture");
                thread.start();
                Handler backgroundHandler = new Handler(thread.getLooper());

                CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        Long effectiveExposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        Log.i("EFFECTIVE EXP TIME", String.valueOf(effectiveExposureTime));

                        Integer effectiveISO = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        Log.i("EFFECTIVE ISO", String.valueOf(effectiveISO));

                        Long effectiveFrameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                        Log.i("EFFECTIVE FRAME DURATION", String.valueOf(effectiveFrameDuration));
                    }
                };

                while(true){

                    if(mRepeatCaptureRunning){
                        //Update the frameRate
                        int frameRate = Integer.parseInt(mCurrentCameraParamsMap.get("capture_per_sec"));
                        sleepTime = 1000 / frameRate;

                        //==========Take capture===========
                        try {
                            mCaptureSession.capture(mShotCaptureBuilder.build(), captureCallback, backgroundHandler);
                        } catch(IllegalStateException e){
                            Log.d(TAG, "CameraDevice was already closed");
                            stopCustomRepeatCapture();
                        }
                        catch (CameraAccessException e) {
                            e.printStackTrace();
                        }

                    }else{
                        //Sleep 1s before checking again if the state of mRepeatCaptureRunning
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    //Sleep the right amount of time to get the expected frame rate
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        mCustomRepeatCaptureThread.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "APPLICATION RESUMED");
    }
    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "APPLICATION PAUSED");
    }
    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "APPLICATION STARTED");
        openCamera();
    }
    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "APPLICATION STOPPED");
        closeCamera();
    }

    //Print in log.i the output sizes supported under JPG format of the camera ID passed in argument
    public void logOutputSizes() {
        //Display output sizes (under jpg format)
        if(mStreamConfigMap == null){
            mStreamConfigMap = mCamCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        }
        Size[] sizes = mStreamConfigMap.getOutputSizes(ImageFormat.JPEG);
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
        Log.i("DISPLAY CAMERA PARAMS", jsonObj.toString());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    mShutterSpeedTextView.setText("Shutter speed: " + "1/" + jsonObj.get("shutter_speed"));
                    mFocusTextView.setText("Focus: " + jsonObj.get("focus_distance"));
                    mIsoTextView.setText("ISO: " + jsonObj.get("iso"));
                    mWbBalanceTextView.setText("W&B balance: " + jsonObj.get("wb_balance"));
                    mcaptureRateTextView.setText("Capture / sec: " + jsonObj.get("capture_per_sec"));
                    moutputSizeTextView.setText("Capture format: " + jsonObj.get("output_size"));
                    moutputRotationTextView.setText("Image rotation: " + jsonObj.get("output_rotation"));
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
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
            if(mStreamConfigMap == null){
                mStreamConfigMap = mCamCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            }

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

    public void closeCamera(){
        if(mCameraDevice != null){
            mCameraDevice.close();
        }
    }



    // To start the preview, the camera must be opened and the texture for the preview must be available
    public void startPreview(){
        Log.i(TAG,"Starting preview");
        //Check cameraDevice object is working
        if(mCameraDevice == null){
            Log.e(TAG, "startPreview fail, Camera Device == null");
            openCamera();
            return;
        } else if (!mTextureView.isAvailable()) {
            Log.e(TAG, "startPreview fail, Texture view is not available");
            mWaitingTextureToStartPreview = true;
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
        HandlerThread thread = new HandlerThread("FileSaving");
        thread.start();
        Handler backgroundHandler = new Handler(thread.getLooper());
        //Create the listener to be called when an image is available in the imageReader
        ImageReader.OnImageAvailableListener imageCaptureListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.i(TAG, "IMAGE AVAILABLE");
                //if(mImageOutputStream == null || mImageServerConnection == null){Log.d(TAG,"Connection to img server not established.");return;}

                //Retrieve the image taken from the shot. Get the byte[] associated to it in order to send the image
                Image capture = imageReader.acquireLatestImage();
                if(capture == null){
                    Log.e(TAG, "capture is null in onImageAvailable().");
                    return;
                }
                Image.Plane[] capturePlane = capture.getPlanes();
                //Let's try first by storing only the [0] Plane
                ByteBuffer captureBuffer = capturePlane[0].getBuffer();
                byte[] captureBytes = new byte[captureBuffer.remaining()];
                captureBuffer.get(captureBytes);

                //Rotate the image
                Bitmap bitmapShot = BitmapFactory.decodeByteArray(captureBytes, 0, captureBytes.length);
                if(bitmapShot == null){Log.e(TAG, "bitmapShot is null in onImageAvailable()."); return;}
                Matrix m = new Matrix();
                m.postRotate(Integer.parseInt(Objects.requireNonNull(mCurrentCameraParamsMap.get("output_rotation"))));
                bitmapShot = Bitmap.createBitmap(bitmapShot, 0, 0, bitmapShot.getWidth(), bitmapShot.getHeight(), m, true);

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bitmapShot.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                byte[] rotatedCaptureBytes = bos.toByteArray();//Data to send to server

                capture.close();


                //POST request to send image
                String serverIp = getResources().getString(R.string.server_ip);
                String imgServerIP = "http://" + serverIp + ":5000/upload_image";
                Log.i("WEB SERVER", "Querying URL: " + imgServerIP);

                try {
                    URL url = new URL(imgServerIP);
                    HttpURLConnection imageServerConnection = (HttpURLConnection) url.openConnection();
                    imageServerConnection.setRequestMethod("POST");
                    imageServerConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    //imageServerConnection.setRequestProperty("Accept", "application/json");
                    imageServerConnection.setRequestProperty("User-Agent", "Mozilla/5.0");
                    imageServerConnection.setDoOutput(true);

                    //Send string test - WORKING
                    /*String jsonInputString = "{name: Upendra, job: Programmer}";
                    OutputStream os = imageServerConnection.getOutputStream();
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                    os.flush();
                    os.close();*/

                    //Prep request
                    String imgFileName = String.valueOf(Calendar.getInstance().getTimeInMillis()) + "_shot.jpg";
                    String boundary =  "*****";
                    imageServerConnection.setRequestProperty("Content-Type", "multipart/form-data;boundary="+boundary);
                    DataOutputStream os = new DataOutputStream(imageServerConnection.getOutputStream());

                    // Send image metadata
                    os.writeBytes("--" + boundary + "\r\n");
                    os.writeBytes("Content-Disposition: form-data; name=\"image\";filename=\"" + imgFileName + "\"\r\n"); // uploaded_file_name is the Name of the File to be uploaded
                    os.writeBytes("\r\n");

                    //Send image
                    InputStream imgInputStream = new ByteArrayInputStream(rotatedCaptureBytes);
                    int maxBufferSize = 1024*1024;
                    int bytesAvailable = imgInputStream.available();
                    int bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    int bytesRead = 1;
                    byte[] buffer = new byte[bufferSize];
                    bytesRead = imgInputStream.read(buffer, 0, bufferSize);
                    while (bytesRead > 0){
                        os.write(rotatedCaptureBytes, 0, bufferSize);
                        bytesAvailable = imgInputStream.available();
                        bufferSize = Math.min(bytesAvailable, maxBufferSize);
                        bytesRead = imgInputStream.read(buffer, 0, bufferSize);
                    }
                    os.writeBytes("\r\n");
                    os.writeBytes(("--" + boundary + "--" + "\r\n"));
                    imgInputStream.close();
                    os.flush();
                    os.close();

                    //Response
                    int responseCode = imageServerConnection.getResponseCode();//THIS LINE SENDS THE POST REQUEST...
                    if (responseCode == HttpURLConnection.HTTP_OK) { //success
                        BufferedReader in = new BufferedReader(new InputStreamReader(imageServerConnection.getInputStream()));
                        String inputLine;
                        StringBuffer response = new StringBuffer();

                        while ((inputLine = in.readLine()) != null) {
                            response.append(inputLine);
                        }
                        in.close();

                        // print result
                        System.out.println(response.toString());
                    } else {
                        System.out.println("POST request did not work. Request code: " + String.valueOf(responseCode));
                    }

                    //bmpImage.compress(Bitmap.CompressFormat.JPEG, 50, mImageOutputStream);
                } catch (ProtocolException e) {
                    Log.d(TAG, "Error sending image to server, ProtocolException", e);
                    throw new RuntimeException(e);
                }catch (IOException e) {
                    Log.d(TAG, "Error sending image to server", e);

                    //Stop capturing
                    stopCustomRepeatCapture();
                    //Update button text on UI thread
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            CharSequence text = mCaptureBtn.isChecked() ? mCaptureBtn.getTextOn() : mCaptureBtn.getTextOff();
                            mCaptureBtn.setText(text);
                            Toast.makeText(getApplicationContext(), "Unable to send pictures to server", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        };
        mImageReader.setOnImageAvailableListener(imageCaptureListener, backgroundHandler);


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
                    mCaptureSession = session;
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

        setupCameraShotRequest();
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
        if(mCurrentCameraParamsMap.get("focus_distance") == null || mCurrentCameraParamsMap.get("shutter_speed") == null || mCurrentCameraParamsMap.get("iso") == null){
            Log.i(TAG, "mCurrentCameraParamsMap has some null fields in updatePreview().");
            return;
        }

        //Enable auto-exposure + auto w-b balance + auto focus
        //mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);
        //Set manual focus
        mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        Float focusDistance = Float.parseFloat(mCurrentCameraParamsMap.get("focus_distance"));
        mPreviewCaptureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);

        //Set Manual W/B balance. Recommended to do before setting AE off
        mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);//Turn off auto white balance
        mPreviewCaptureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);//Turn off auto white balance
        RggbChannelVector gain = colorTemperature(Integer.parseInt(mCurrentCameraParamsMap.get("wb_balance")));
        mPreviewCaptureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gain);

        //Set exposure time
        mPreviewCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);//Required to set manual exposure time
        Long exposureTime = Long.parseLong(mCurrentCameraParamsMap.get("shutter_speed"));
        mPreviewCaptureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);

        //Set ISO level of the preview according to the camera params stored in mCurrentCameraParamsMap
        Integer iso = Integer.parseInt(mCurrentCameraParamsMap.get("iso"));
        mPreviewCaptureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);


        //Create a thread to send capture request in loop.
        HandlerThread thread = new HandlerThread("CameraPreview");
        thread.start();
        Handler backgroundHandler = new Handler(thread.getLooper());

        try {
            mCaptureSession.setRepeatingRequest(mPreviewCaptureBuilder.build(), null, backgroundHandler);
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
        if (!mTextureView.isAvailable()) {
            Log.e(TAG, "setupCameraShotRequest fail, Texture view is not available");
            return;
        }
        if(mTextureView == null){
            Log.e(TAG, "mTextureView is NULL");
            return;
        }

        //Create a capture request for shots taking
        try {
            mShotCaptureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        //Connect the camera preview to the surface + image reader to save the shots
        mShotCaptureBuilder.addTarget(mImageReader.getSurface());

        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if(null == texture) {
            Log.e(TAG,"texture is null, return");
            return;
        }
        //Initialise texture & surface for preview
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(texture);
        mShotCaptureBuilder.addTarget(previewSurface);

        //Get the parameter values supported by the camera
        //Supported output formats (JPEG...)
        int[] formats = mStreamConfigMap.getOutputFormats();
        Log.i("AVAILABLE IMG FORMATS: ", Arrays.toString(formats));
        //Focus distance
        Float minFocusDistance = mCamCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Log.i("MIN FOCUS DISTANCE: ", minFocusDistance.toString());
        //Exposure range
        Range<Long> exposureRange = mCamCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Log.i("EXPOSURE RANGE: ", exposureRange.toString());
        //ISO range
        Range<Integer> sensitivityRange = mCamCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Log.i("ISO RANGE: ", sensitivityRange.toString());
        //Min frame rate
        String outpuSizeStr = mCurrentCameraParamsMap.get("output_size");
        Size outputCaptureSize = Size.parseSize(outpuSizeStr);
        long minFrameRate = mStreamConfigMap.getOutputMinFrameDuration(ImageFormat.JPEG, outputCaptureSize);
        Log.i("Min frame duration: ", Long.toString(minFrameRate));

        //Set all parameters
        if(mShotCaptureBuilder == null) {
            Log.e(TAG, "takeShot error: capture builder is null");
            return;
        }
        if(mCurrentCameraParamsMap.get("focus_distance") == null || mCurrentCameraParamsMap.get("shutter_speed") == null || mCurrentCameraParamsMap.get("iso") == null){
            Log.i(TAG, "mCurrentCameraParamsMap has some null fields in takeShot().");
            return;
        }
        //Set Focus
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        Float focusDistance = Float.parseFloat(mCurrentCameraParamsMap.get("focus_distance"));
        mShotCaptureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);

        //Set Manual W/B balance. Recommended to do before setting AE off
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);//Turn off auto white balance
        mShotCaptureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);//Turn off auto white balance
        RggbChannelVector gain = colorTemperature(Integer.parseInt(mCurrentCameraParamsMap.get("wb_balance")));
        mShotCaptureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gain);

        //Set Exposure time
        mShotCaptureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);//Required to set manual exposure time
        Long exposureTime = Long.parseLong(mCurrentCameraParamsMap.get("shutter_speed"));
        mShotCaptureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);

        //Set ISO sensitivity
        Integer iso = Integer.parseInt(mCurrentCameraParamsMap.get("iso"));
        mShotCaptureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);

        //Print sleep time
        int frameRate = Integer.parseInt(mCurrentCameraParamsMap.get("capture_per_sec"));
        int sleepTime = 1000 / frameRate;
        Log.i("Sleeping time:", Long.toString(sleepTime));
    }

    public void startCustomRepeatCapture(){
        mRepeatCaptureRunning = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //Disable refresh camera params during capture mode
                mRefreshBtn.setEnabled(false);
                mCaptureBtn.setChecked(true);
            }
        });
    }

    //Stop running the capture thread, and update buttons
    public void stopCustomRepeatCapture(){
        mRepeatCaptureRunning = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //Enable back refresh camera params
                mRefreshBtn.setEnabled(true);
                mCaptureBtn.setChecked(false);
            }
        });
    }

    // ===== SERVERS REQUESTS =====
    //Requests to camera param server and image processing server

    //Send a POST request to the image processing server with the new capture session ID (= count)
    // When response received, the capture starts
    public class startNewCaptureSession extends Thread{
        @Override
        public void run() {
            String serverIp = getResources().getString(R.string.server_ip);
            String imgServerIP = "http://" + serverIp + ":5000/new_capture_session";
            Log.i("WEB SERVER", "Querying URL: " + imgServerIP);

            try {
                URL url = new URL(imgServerIP);
                HttpURLConnection imageServerConnection = (HttpURLConnection) url.openConnection();
                imageServerConnection.setRequestMethod("POST");
                imageServerConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                //imageServerConnection.setRequestProperty("Accept", "application/json");
                imageServerConnection.setRequestProperty("User-Agent", "Mozilla/5.0");
                imageServerConnection.setDoOutput(true);

                //Send the capture session ID = count
                String boundary = "*****";
                imageServerConnection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
                DataOutputStream os = new DataOutputStream(imageServerConnection.getOutputStream());

                //Send capture session number
                os.writeBytes("--" + boundary + "\r\n");
                os.writeBytes("Content-Disposition: form-data; name=\"capture_session_count\"\r\n"); // uploaded_file_name is the Name of the File to be uploaded
                os.writeBytes("\r\n");
                os.writeBytes(Integer.toString(mCaptureSessionCount));
                os.writeBytes("\r\n");
                os.writeBytes("--" + boundary + "--\r\n");

                //Response
                int responseCode = imageServerConnection.getResponseCode();//THIS LINE SENDS THE POST REQUEST...
                if (responseCode == HttpURLConnection.HTTP_OK) { //success
                    BufferedReader in = new BufferedReader(new InputStreamReader(imageServerConnection.getInputStream()));
                    String inputLine;
                    StringBuffer response = new StringBuffer();

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    // print result
                    System.out.println(response.toString());

                    //Start capture session
                    //Start repeat capture thread
                    startCustomRepeatCapture();
                } else {
                    System.out.println("/new_capture_session POST request did not work. Request code: " + String.valueOf(responseCode));
                }

            } catch (IOException e) {
                Log.e(TAG, "IOException thrown when sending new capture session ID: " + e);
                stopCustomRepeatCapture();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Unable to send capture session ID to server", Toast.LENGTH_LONG).show();
                    }
                });

            }
        }
    }

    public class closeCaptureSession extends Thread{
        @Override
        public void run() {
            //First stop the thread capturing images from the phone
            stopCustomRepeatCapture();

            //Send close request to img processing server
            String serverIp = getResources().getString(R.string.server_ip);
            String imgServerIP = "http://" + serverIp + ":5000/close_capture_session";
            Log.i("WEB SERVER", "Querying URL: " + imgServerIP);

            try {
                URL url = new URL(imgServerIP);
                HttpURLConnection imageServerConnection = (HttpURLConnection) url.openConnection();
                imageServerConnection.setRequestMethod("GET");
                imageServerConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                //imageServerConnection.setRequestProperty("Accept", "application/json");
                imageServerConnection.setRequestProperty("User-Agent", "Mozilla/5.0");

                //Response
                int responseCode = imageServerConnection.getResponseCode();//THIS LINE SENDS THE GET REQUEST...
                if (responseCode == HttpURLConnection.HTTP_OK) { //success
                    BufferedReader in = new BufferedReader(new InputStreamReader(imageServerConnection.getInputStream()));
                    String inputLine;
                    StringBuffer response = new StringBuffer();

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    // print result
                    System.out.println(response.toString());
                } else {
                    System.out.println("/close_capture_session GET request did not work. Request code: " + String.valueOf(responseCode));
                }

            } catch (IOException e) {
                Log.e(TAG, "IOException thrown when closing the current capture session: " + e);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Unable to send close capture session request to server", Toast.LENGTH_LONG).show();
                    }
                });

            }
        }
    }

    //Class to retrieve JSON file with camera parameters from remote server to ease config without rebuilding the app, and without building UI
    //Whenever the JSON file is retrieved from the server, the preview is updated with the new parameters. updatePreview() is called.
    public class fetchCameraParams extends Thread{
        String data = "";
        JSONObject jsonFile = null;

        @Override
        public void run() {
            String serverIp = getResources().getString(R.string.server_ip);
            String fileURLStr = "http://" + serverIp + ":8080/camera_parameters.json";
            Log.i("WEB SERVER", "Querying URL: " + fileURLStr);

            try{
                //Init jsonFile with dummy values
                jsonFile = new JSONObject();

                //HTTP REQUEST
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
                        mCurrentCameraParamsMap.put("focus_distance", jsonFile.getString("focus_distance"));
                        mCurrentCameraParamsMap.put("iso", jsonFile.getString("iso"));
                        mCurrentCameraParamsMap.put("wb_balance", jsonFile.getString("wb_balance"));
                        mCurrentCameraParamsMap.put("capture_per_sec", jsonFile.getString("capture_per_sec"));
                        mCurrentCameraParamsMap.put("output_size", jsonFile.getString("output_size"));
                        mCurrentCameraParamsMap.put("output_rotation", jsonFile.getString("output_rotation"));
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }

                    // Save config file
                    FileOutputStream fOut = openFileOutput(getResources().getString(R.string.camera_config_file),Context.MODE_PRIVATE);
                    fOut.write(jsonFile.toString().getBytes());
                    fOut.close();
                    Log.i(TAG, "Successfully wrote config to file");

                    //Display new config
                    MainActivity.this.displayCameraParams(jsonFile);

                    //This is purely random. If startpreview (and createCaptureSession) is not called from UI thread, it throw error "IllegalArgumentException: No handler given, and current thread has no looper!"
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            //Start preview
                            startPreview();

                            //Enable capture button
                            mCaptureBtn.setEnabled(true);
                        }
                    });
                }
                else{
                    Log.e(TAG, "JSON FILE IS EMPTY");
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "MalformedURLException thrown when retrieving JSON file with camera params");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Unable to receive config data from server", Toast.LENGTH_LONG).show();
                    }
                });

            }
        }
    }

    public void loadCamConfigFromFile() {

        JSONObject jsonConfig = new JSONObject();// JSON object that will receive the file content
        //Initialise config with dummy values
        try {
            jsonConfig.put("shutter_speed", "1000000");
            jsonConfig.put("focus_distance", "1");
            jsonConfig.put("iso", "500");
            jsonConfig.put("wb_balance", "50");
            jsonConfig.put("capture_per_sec", "1");
            jsonConfig.put("output_size", "720x480");
            jsonConfig.put("output_rotation", "90");
        } catch (JSONException ex) {
            Log.e(TAG, "Error while creating JSON config object");
            throw new RuntimeException(ex);
        }

        //READ config file
        String text = "";
        try {
            FileInputStream fin = openFileInput(getResources().getString(R.string.camera_config_file));
            int c;
            while( (c = fin.read()) != -1){
                text = text + Character.toString((char)c);
            }
            fin.close();
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Error, config file not found");
        } catch (IOException e) {
            Log.e(TAG, "Error reading config file");
        }


        //Fill in the JSON object with the file just read
        if(text.length() > 0){
            try {
                jsonConfig = new JSONObject(String.valueOf(text));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            //Extract data from JSON to array map
            mCurrentCameraParamsMap.put("shutter_speed", jsonConfig.getString("shutter_speed"));
            mCurrentCameraParamsMap.put("focus_distance", jsonConfig.getString("focus_distance"));
            mCurrentCameraParamsMap.put("iso", jsonConfig.getString("iso"));
            mCurrentCameraParamsMap.put("wb_balance", jsonConfig.getString("wb_balance"));
            mCurrentCameraParamsMap.put("capture_per_sec", jsonConfig.getString("capture_per_sec"));
            mCurrentCameraParamsMap.put("output_size", jsonConfig.getString("output_size"));
            mCurrentCameraParamsMap.put("output_rotation", jsonConfig.getString("output_rotation"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        finally {
            MainActivity.this.displayCameraParams(jsonConfig);
        }

    }

    //Return the object used as parameter for COLOR_CORRECTION_GAINS
    public static RggbChannelVector colorTemperature(int whiteBalance) {
        return new RggbChannelVector(0.635f + (0.0208333f * whiteBalance), 1.0f, 1.0f, 3.7420394f + (-0.0287829f * whiteBalance));
    }
}

