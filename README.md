# Camera parameters
The parameters are:  "focus_distance", "shutter_speed", "iso", "wb_balance", "capture_per_sec", "output_size", "output_rotation"
They are stored in a local webserver, and accessed through http://<server_ip>:8080/camera_parameters.json.

To get the available values of each parameter, look at the logs of the app.

For instance with a Samsung A8:
The values for focus_distance ranges from [0.0 = infinity, to 10.0]
shutter_speed: [22000, 100000000]
iso: [64,1600]