package com.dddog.main;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.dddog.R;
import com.dddog.audio.encoder.AmrAudioEncoder;
import com.dddog.audio.player.AmrAudioPlayer;

//blog.csdn.net/zgyulongfei
//Email: zgyulongfei@gmail.com

public class MainActivity extends Activity {

	private Button startEncodeButton, stopEncodeButton;
	private Button startPlayButton, stopPlayButton;

	private AmrAudioEncoder amrEncoder;
	private AmrAudioPlayer audioPlayer;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		initControls();
//		startEncodeButton.performClick();
	}

	private void initControls() {
		startEncodeButton = (Button) findViewById(R.id.startEncode);
		startEncodeButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startEncodeButton.setEnabled(false);
				startEncodeAudio();
				stopEncodeButton.setEnabled(true);
			}
		});

		stopEncodeButton = (Button) findViewById(R.id.stopEncode);
		stopEncodeButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				stopEncodeButton.setEnabled(false);
				stopEncodeAudio();
				startEncodeButton.setEnabled(true);
			}
		});
		stopEncodeButton.setEnabled(false);

		startPlayButton = (Button) findViewById(R.id.startPlay);
		startPlayButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startPlayButton.setEnabled(false);
				startPlayAudio();
				stopPlayButton.setEnabled(true);
			}
		});

		stopPlayButton = (Button) findViewById(R.id.stopPlay);
		stopPlayButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				stopPlayButton.setEnabled(false);
				stopPlayAudio();
				startPlayButton.setEnabled(true);
			}
		});
		stopPlayButton.setEnabled(false);
	}

	private void startEncodeAudio() {
		amrEncoder = AmrAudioEncoder.getArmAudioEncoderInstance();
		amrEncoder.initArmAudioEncoder(this);
		amrEncoder.start();
	}

	private void stopEncodeAudio() {
		if (amrEncoder != null) {
			amrEncoder.stop();
		}
	}

	private void startPlayAudio() {
		audioPlayer = AmrAudioPlayer.getAmrAudioPlayerInstance();
		audioPlayer.initAmrAudioPlayer(this);
		audioPlayer.start();
	}

	private void stopPlayAudio() {
		if (audioPlayer != null) {
			audioPlayer.stop();
		}
	}

	@Override
	protected void onDestroy() {
		try {
			if (amrEncoder != null) {
				amrEncoder.stop();
			}
			if (audioPlayer != null) {
				audioPlayer.stop();
			}
		} catch (Exception e) {
		}
		super.onDestroy();
	}

}