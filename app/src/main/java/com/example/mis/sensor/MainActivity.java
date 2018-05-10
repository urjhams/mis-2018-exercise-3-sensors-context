package com.example.mis.sensor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;

public class MainActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private double[] freqCounts;

    GraphView graphAcceleration;
    GraphView graphF;

    private LineGraphSeries<DataPoint> seriesX;
    private LineGraphSeries<DataPoint> seriesY;
    private LineGraphSeries<DataPoint> seriesZ;
    private LineGraphSeries<DataPoint> seriesM;
    private LineGraphSeries<DataPoint> fftSeries;

    LocationManager locationManager;

    private Queue<Double> xCalculateQueue = new LinkedList<>();

    private static final int MY_PERMISSIONS_REQUEST_READ_CONTACTS = 1;
    private int winSize = 32;
    private int graphRange = 500;
    private int currentX = 0;
    private boolean isPlaying = false;
    private boolean isAccelerationThinkMoving = false;
    private UserLocationState userState = UserLocationState.standing;
    private boolean isFastestSensor = false;
    private Location lastLocation;

    MediaPlayer joggingPlayer;
    MediaPlayer activitiesPlayer;

    Button playButton;
    TextView statusTextView;

    public MainActivity() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Objects.requireNonNull(getSupportActionBar()).hide();
        }

        setContentView(R.layout.activity_main);

        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        seekBarRegister();
        prepareSensor();
        prepareGraph();
        preparePlayer();
        if (!isGrantedPermission()) {
            askForLocationPermission();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        joggingPlayer.release();
        activitiesPlayer.release();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL);
        preparePlayer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_CONTACTS) {
            switch (requestCode) {
                case MY_PERMISSIONS_REQUEST_READ_CONTACTS: {
                    if (grantResults.length > 0 &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
                        // permission granted
                        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
                    }
                }
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            getAccelerometer(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onLocationChanged(Location location) {
        double currentSpeed = 0;
        double caculatedSpeed = 0;
        if (lastLocation != null) {
            double elapseTime = (location.getTime() - lastLocation.getTime()) / 1000;
            caculatedSpeed = lastLocation.distanceTo(location) / elapseTime;
        }
        lastLocation = location;

        currentSpeed = location.hasSpeed() ? location.getSpeed() : caculatedSpeed;

        if (location != null) {
            currentSpeed = location.getSpeed() * 3600 / 1000;   // from m/s to km/h
        }
        if (currentSpeed < 1) { // standing
            userState = UserLocationState.standing;
        }
        if (currentSpeed > 1 && currentSpeed <= 13 ) { //walking
            userState = UserLocationState.walking;
        }
        if (currentSpeed > 13 && currentSpeed <= 25) { //riding bike (activity) or running
            userState = UserLocationState.running;
        }
        if (currentSpeed > 25) {
            userState = UserLocationState.onVehicle;
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @SuppressLint("StaticFieldLeak")
    private class FFTAsynctask extends AsyncTask<double[], Void, double[]> {

        private int wsize; //window size must be power of 2

        // constructor to set window size
        FFTAsynctask(int wdsize) {
            this.wsize = wdsize;
        }

        @Override
        protected double[] doInBackground(double[]... values) {

            double[] realPart = values[0].clone(); // actual acceleration values
            double[] imgPart = new double[wsize]; // init empty

            FFT fft = new FFT(wsize);
            fft.fft(realPart, imgPart);
            //init new double array for magnitude (e.g. frequency count)
            double[] magnitude = new double[wsize];

            //fill array with magnitude values of the distribution
            for (int i = 0; wsize > i ; i++) {
                magnitude[i] = Math.sqrt(Math.pow(realPart[i], 2) + Math.pow(imgPart[i], 2));
            }
            return magnitude;
        }

        @Override
        protected void onPostExecute(double[] values) {
            freqCounts = values;
            drawFFTGraph();
        }
    }

    private void drawFFTGraph() throws ArrayIndexOutOfBoundsException, NullPointerException {
        int currentXAxis = 0;
        DataPoint[] list = new DataPoint[winSize];
        for (int index = 0; index < freqCounts.length; index++) {

            DataPoint point = new DataPoint(currentXAxis, freqCounts[index]);
            list[index] = point;
            currentXAxis += 5;
        }
        fftSeries = new LineGraphSeries<>(list);
        graphF.removeAllSeries();
        graphF.addSeries(fftSeries);
    }

    private void seekBarRegister() {
        //------init
        SeekBar rateBar = findViewById(R.id.rateBar);
        SeekBar sizeBar = findViewById(R.id.WSizeBar);

        //------configuration
        sizeBar.setProgress(0);
        sizeBar.setMax(5); // 5 is enough until 1024, too much will crash (cause background task)
        rateBar.setProgress(0);
        rateBar.setMax(3);

        //------ listener
        rateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int delay = 0;
                switch (seekBar.getProgress()) {
                    case 0:
                        delay = SensorManager.SENSOR_DELAY_NORMAL;
                        isFastestSensor = false;
                        break;
                    case 1:
                        delay = SensorManager.SENSOR_DELAY_UI;
                        isFastestSensor = false;
                        break;
                    case 2:
                        delay = SensorManager.SENSOR_DELAY_GAME;
                        isFastestSensor = false;
                        break;
                    case 3:
                        delay = SensorManager.SENSOR_DELAY_FASTEST;
                        isFastestSensor = true;
                        break;
                }
                refreshSensor(delay);
            }
        });

        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int size = 0;
                switch (seekBar.getProgress()) {
                    case 0:
                        size = 32;
                        break;
                    case 1:
                        size = 64;
                        break;
                    case 2:
                        size = 128;
                        break;
                    case 3:
                        size = 256;
                        break;
                    case 4:
                        size = 512;
                        break;
                    case 5:
                        size = 1024;
                        break;
                }
                xCalculateQueue = new LinkedList<>();
                winSize = size;
                graphF.removeAllSeries();
                graphF.getViewport().setMaxX(winSize);
            }
        });
    }

    //---------------- local functions


    private void preparePlayer() {
        playButton = findViewById(R.id.btnPlayer);
        statusTextView = findViewById(R.id.statusText);
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Button reference = (Button) v;
                isPlaying = !isPlaying;
                reference.setText((isPlaying) ? "Stop" : "Play");
            }
        });
        if (joggingPlayer == null) {
            joggingPlayer = MediaPlayer.create(this, R.raw.alone);
            joggingPlayer.setLooping(true);
        }
        if (activitiesPlayer == null) {
            activitiesPlayer = MediaPlayer.create(this, R.raw.sex);
            activitiesPlayer.setLooping(true);
        }
    }

    private void getAccelerometer(SensorEvent event) {

        float[] values = event.values;

        double x = values[0];
        double y = values[1];
        double z = values[2];

        double accelerationToSqrt = (x * x + y * y + z * z) /
                (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH);

        double currentAcceleration = Math.sqrt(accelerationToSqrt);

        isAccelerationThinkMoving = currentAcceleration > 1;

        double magnitude = Math.sqrt(x * x + y * y + z * z);

        accelerationUpdate(x,y,z,magnitude);

        xCalculateQueue.add(magnitude);

        if (xCalculateQueue.size() > winSize) {
            xCalculateQueue.remove();
        }

        // cause too fast sensor means there will have the case
        // that the async task will have input array longer than execute post input
        if (currentX % ((isFastestSensor) ? 500 : 150) == 0) {
            int index = 0;
            double[] input = new double[winSize];
            for (Double element : xCalculateQueue) {
                input[index] = element;
                index++;
            }
            new FFTAsynctask(winSize).execute(input);
        }
        currentX += 5;

        playingMusic();
    }


    private void prepareSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager != null ?
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) :
                null;
    }

    private void prepareGraph() {
        //--------------- graph init

        graphAcceleration = findViewById(R.id.graphX);
        graphF = findViewById(R.id.fftGraph);

        seriesX = new LineGraphSeries<>();
        initSeries(seriesX,Color.RED);

        seriesY = new LineGraphSeries<>();
        initSeries(seriesY,Color.GREEN);

        seriesZ = new LineGraphSeries<>();
        initSeries(seriesZ,Color.CYAN);

        seriesM = new LineGraphSeries<>();
        initSeries(seriesM,Color.MAGENTA);

        fftSeries = new LineGraphSeries<>();
        initSeries(fftSeries,Color.BLUE);

        setStyleOf(graphAcceleration,"x : red, y: green, z: cyan, magnitude: magenta",
                -20,60,seriesX,graphRange);
        setStyleOf(graphAcceleration,"x : red, y: green, z: cyan, magnitude: magenta",
                -20,60,seriesY,graphRange);
        setStyleOf(graphAcceleration,"x : red, y: green, z: cyan, magnitude: magenta",
                -20,60,seriesZ,graphRange);
        setStyleOf(graphAcceleration,"x : red, y: green, z: cyan, magnitude: magenta",
                -20,60,seriesM,graphRange);
        setStyleOf(graphF,"FFT",0,200,fftSeries,winSize);

    }

    private void initSeries(LineGraphSeries<DataPoint> series, int color) {
        series.setColor(color);
        series.setDataPointsRadius(10);
    }

    private void setStyleOf(GraphView graph,
                            String name,
                            double minY,
                            double maxY,
                            LineGraphSeries<DataPoint> source,
                            int range) {
        graph.addSeries(source);
        graph.setTitle(name);
        graph.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.HORIZONTAL);
        graph.getGridLabelRenderer().setHorizontalLabelsColor(Color.WHITE);

        Viewport viewPort = graph.getViewport();
        viewPort.setScrollable(false);
        viewPort.setScalable(false);

        viewPort.setXAxisBoundsManual(true);
        viewPort.setMinX(0);
        viewPort.setMaxX(range);

        viewPort.setYAxisBoundsManual(true);
        viewPort.setMinY(minY);
        viewPort.setMaxY(maxY);
    }

    private void accelerationUpdate(final double xAxis,
                                    final double yAxis,
                                    final double zAxis,
                                    final double mag) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                seriesX.appendData(new DataPoint(currentX, xAxis),true,graphRange);
                seriesY.appendData(new DataPoint(currentX, yAxis),true,graphRange);
                seriesZ.appendData(new DataPoint(currentX, zAxis),true,graphRange);
                seriesM.appendData(new DataPoint(currentX, mag),true,graphRange);
            }
        });
    }

    private void refreshSensor(int delay) {
        sensorManager.unregisterListener(this);
        sensorManager.registerListener(this,accelerometer,delay);
    }

    @SuppressLint("SetTextI18n")
    private void playingMusic() {
        try {
            if(isPlaying) {
                if (isAccelerationThinkMoving) {
                    switch (userState) {
                        case walking:
                            statusTextView.setText("is Walking");
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                            if (!joggingPlayer.isPlaying()) joggingPlayer.start();
                            break;
                        case running:
                            statusTextView.setText("is Running or On Bycle");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (!activitiesPlayer.isPlaying()) activitiesPlayer.start();
                            break;
                        case standing:
                            statusTextView.setText("is Standing");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                            break;
                        case onVehicle:
                            statusTextView.setText("is on a car");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                            break;
                    }
                } else {
                    switch (userState) {
                        case walking:
                            statusTextView.setText("seem u re not walking");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                        case running:
                            statusTextView.setText("seem u re in a vehicle");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                            break;
                        case standing:
                            statusTextView.setText("is Standing");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                            break;
                        case onVehicle:
                            statusTextView.setText("is on a car");
                            if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                            if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
                            break;
                    }
                }
            } else {
                statusTextView.setText("");
                if (joggingPlayer.isPlaying()) joggingPlayer.pause();
                if (activitiesPlayer.isPlaying()) activitiesPlayer.pause();
            }

        } catch (IllegalStateException ex) {
            System.out.print("IllegalStateException: " + ex.getLocalizedMessage());
        }
    }

    private void askForLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},
                MY_PERMISSIONS_REQUEST_READ_CONTACTS);
    }
    private boolean isGrantedPermission() {
        return (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) &&
                (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED);
    }
}

enum UserLocationState {
    standing,
    walking,
    running,
    onVehicle
}
