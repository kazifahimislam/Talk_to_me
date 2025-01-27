package com.example.talktome;

import static android.content.ContentValues.TAG;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;


import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.talktome.ml.Model;
import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int requestCode =1;
    private static final int VOICE_INPUT_REQUEST_CODE = 2;
    ImageButton cameraButton;
    FloatingActionButton cancelChat;
    ImageButton micButton;
    ImageView capturedImg;

    EditText messageBox;

    ImageButton sendButton;

    private TextToSpeech textToSpeech;

    int imageSize = 32;
    TextView outputText;
    private String apiKey = "AIzaSyCaRYnDFD63KL90E7rqDjFudBtK2yLZq7E";
    private GenerativeModelFutures model;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        cancelChat = findViewById(R.id.cancelChat);
        cancelChat.setVisibility(View.GONE);
        cameraButton = findViewById(R.id.cameraButton);
        capturedImg = findViewById(R.id.capturedImg);
        messageBox = findViewById(R.id.messageBox);
        sendButton = findViewById(R.id.sendButton);
        outputText = findViewById(R.id.outputText);

        cameraButton.setOnClickListener(v -> {
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityIfNeeded(cameraIntent, requestCode);
        });

        textToSpeech = new TextToSpeech(this, this);


        sendButton.setOnClickListener(v -> {
            String prompt = messageBox.getText().toString();
            gemini_prompt(prompt);

        });
        micButton = findViewById(R.id.micButton);
        micButton.setOnClickListener(v -> startVoiceInput());
        cancelChat.setOnClickListener(v -> {
            textToSpeech.stop();
            cancelChat.setVisibility(View.GONE);
        });

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.requestCode && resultCode == RESULT_OK)
        {
            assert data != null;
            Bitmap image = (Bitmap) data.getExtras().get("data") ;
            assert image != null;
            int dimension = Math.min(image.getWidth(),image.getHeight());
            image = ThumbnailUtils.extractThumbnail(image,dimension,dimension);
            
//            assert data != null;
//            Bundle extras = data.getExtras();
//            assert extras != null;
            capturedImg.setImageBitmap(image);
            gemini_prompt(image);
            image = Bitmap.createScaledBitmap(image,imageSize,imageSize,false);

//            classifyImage(image);
        }
        else if (requestCode == VOICE_INPUT_REQUEST_CODE && resultCode == RESULT_OK) {
            assert data != null;
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String recognizedText = result.get(0);
                Log.d(TAG, "Recognized text: " + recognizedText);
                gemini_prompt(recognizedText);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...");
        try {
            startActivityIfNeeded(intent, VOICE_INPUT_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Voice input failed", e);
        }
    }

    public void classifyImage(Bitmap image) {
        try {
            Model model = Model.newInstance(getApplicationContext());

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 32, 32, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3);
            byteBuffer.order(ByteOrder.nativeOrder());
            int [] intValues = new int[imageSize * imageSize];

            image.getPixels(intValues,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
           int pixel = 0;
            for (int i = 0; i<imageSize; i++){
                for (int j = 0; j< imageSize; j++){

                    int val = intValues[pixel++];//RGB
                    byteBuffer.putFloat(((val >> 16)& 0xFF)*(1.f/1));
                    byteBuffer.putFloat(((val >> 8)& 0xFF)*(1.f/1));
                    byteBuffer.putFloat((val & 0xFF)*(1.f/1));
                }
            }
            inputFeature0.loadBuffer(byteBuffer);

            // Runs model inference and gets result.
            Model.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();
            float [] confidence = outputFeature0.getFloatArray();
            int maxIndex = 0;
            float maxConfidence = 0;

            for (int i = 0; i < confidence.length; i++) {
                if (confidence[i] > maxConfidence) {
                    maxConfidence = confidence[i];
                    maxIndex = i;
                }
            }
            String[] classes = {"Apple", "Banana", "Orange"};
            String result = classes[maxIndex];
            if (result.equals("Apple") || result.equals("Banana") || result.equals("Orange")){
            speak("I Think This is an image of " + result);}

            // Releases model resources if no longer used.
            model.close();
        } catch (IOException e) {
            // TODO Handle the exception
        }

    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.UK);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported");
            } else {
                // TTS is ready to use
                speak("Hi, how can I help you today?");
            }
        } else {
            Log.e("TTS", "Initialization failed");
        }
    }

    private void speak(String text) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);

    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
    private void gemini_prompt(String prompt) {

        // The Gemini 1.5 models are versatile and work with both text-only and multimodal prompts
        GenerativeModel gm = new GenerativeModel(/* modelName */ "gemini-1.5-flash",
// Access your API key as a Build Configuration variable (see "Set up your API key" above)
                /* apiKey */apiKey);
        GenerativeModelFutures model = GenerativeModelFutures.from(gm);

        Content content = new Content.Builder()
                .addText(prompt)
//                .addImage()
                .build();
        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {
                    String resultText = result.getText();
                    outputText.setText(resultText);
                    speak(resultText);
                    cancelChat.setVisibility(View.VISIBLE);
                }

                @Override
                public void onFailure(Throwable t) {
                    t.printStackTrace();
                }
            }, this.getMainExecutor());
        }
    }
    private void gemini_prompt(Bitmap image) {

        // The Gemini 1.5 models are versatile and work with both text-only and multimodal prompts
        GenerativeModel gm = new GenerativeModel(/* modelName */ "gemini-1.5-flash",
// Access your API key as a Build Configuration variable (see "Set up your API key" above)
                /* apiKey */apiKey);
        GenerativeModelFutures model = GenerativeModelFutures.from(gm);

        Content content = new Content.Builder()
                .addText("what is this?")
                .addImage(image)
                .build();
        ListenableFuture<GenerateContentResponse> response = model.generateContent(content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {
                    String resultText = result.getText();
                    outputText.setText(resultText);
                    speak(resultText);

                    cancelChat.setVisibility(View.VISIBLE);



                }

                @Override
                public void onFailure(Throwable t) {
                    t.printStackTrace();
                }
            }, this.getMainExecutor());
        }
    }
}
