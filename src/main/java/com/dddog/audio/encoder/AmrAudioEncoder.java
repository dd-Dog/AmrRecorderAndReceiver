package com.dddog.audio.encoder;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import com.dddog.config.CommonConfig;

import android.app.Activity;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.widget.Toast;

//blog.csdn.net/zgyulongfei
//Email: zgyulongfei@gmail.com

public class AmrAudioEncoder {
	private static final String TAG = "ArmAudioEncoder";

	private static AmrAudioEncoder amrAudioEncoder = null;

	private Activity activity;

	private MediaRecorder audioRecorder;

	private boolean isAudioRecording;

	private LocalServerSocket lss;
	private LocalSocket sender, receiver;

	private AmrAudioEncoder() {
	}

	public static AmrAudioEncoder getArmAudioEncoderInstance() {
		if (amrAudioEncoder == null) {
			synchronized (AmrAudioEncoder.class) {
				if (amrAudioEncoder == null) {
					amrAudioEncoder = new AmrAudioEncoder();
				}
			}
		}
		return amrAudioEncoder;
	}

	public void initArmAudioEncoder(Activity activity) {
		this.activity = activity;
		isAudioRecording = false;
	}

	public void start() {
		if (activity == null) {
			showToastText("音频编码器未初始化，请先执行init方法");
			return;
		}

		if (isAudioRecording) {
			showToastText("音频已经开始编码，无需再次编码");
			return;
		}

		if (!initLocalSocket()) {
			showToastText("本地服务开启失败");
			releaseAll();
			return;
		}

		if (!initAudioRecorder()) {
			showToastText("音频编码器初始化失败");
			releaseAll();
			return;
		}

		this.isAudioRecording = true;
		startAudioRecording();
	}

	private boolean initLocalSocket() {
		boolean ret = true;
		try {
			releaseLocalSocket();

			String serverName = "armAudioServer";
			//缓存大小
			final int bufSize = 1024;

			//创建服务器端Unix域套接字，与LocalSocket对应
			lss = new LocalServerSocket(serverName);
			//LocalSocket是对Linux中Socket进行了封装，采用JNI方式调用，实现进程间通信。
			receiver = new LocalSocket();
			//与LSS建立连接
			receiver.connect(new LocalSocketAddress(serverName));
			receiver.setReceiveBufferSize(bufSize);
			receiver.setSendBufferSize(bufSize);

			//建立连接完成
			sender = lss.accept();
			sender.setReceiveBufferSize(bufSize);
			sender.setSendBufferSize(bufSize);
		} catch (IOException e) {
			ret = false;
		}
		return ret;
	}

	private boolean initAudioRecorder() {
		if (audioRecorder != null) {
			audioRecorder.reset();
			audioRecorder.release();
		}

		//使用MediaRecorder进行录音
		audioRecorder = new MediaRecorder();
		//录制mic声音
		audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.RAW_AMR);
		final int mono = 1;
		audioRecorder.setAudioChannels(mono);
		audioRecorder.setAudioSamplingRate(8000);
		audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
		//设置录音输出路径
		Log.d(TAG, "outFile=" + sender.getFileDescriptor());
		audioRecorder.setOutputFile(sender.getFileDescriptor());
//		String externalStorageDirectory = Environment.getExternalStorageDirectory().getPath();
//		File outFile = new File(externalStorageDirectory, "record");
//		audioRecorder.setOutputFile(externalStorageDirectory + "/" + "record");

		boolean ret = true;
		try {
			audioRecorder.prepare();
			audioRecorder.start();
		} catch (Exception e) {
			releaseMediaRecorder();
			showToastText("手机不支持录音此功能");
			ret = false;
		}
		return ret;
	}

	private void startAudioRecording() {
		Log.d(TAG, "startAudioRecording");
		new Thread(new AudioCaptureAndSendThread()).start();
	}

	public void stop() {
		if (isAudioRecording) {
			isAudioRecording = false;
		}
		releaseAll();
	}

	private void releaseAll() {
		releaseMediaRecorder();
		releaseLocalSocket();
		amrAudioEncoder = null;
	}

	private void releaseMediaRecorder() {
		try {
			if (audioRecorder == null) {
				return;
			}
			if (isAudioRecording) {
				audioRecorder.stop();
				isAudioRecording = false;
			}
			audioRecorder.reset();
			audioRecorder.release();
			audioRecorder = null;
		} catch (Exception err) {
			Log.d(TAG, err.toString());
		}
	}

	private void releaseLocalSocket() {
		try {
			if (sender != null) {
				sender.close();
			}
			if (receiver != null) {
				receiver.close();
			}
			if (lss != null) {
				lss.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		sender = null;
		receiver = null;
		lss = null;
	}

	private boolean isAudioRecording() {
		Log.d(TAG, "isAudioRecording=" + isAudioRecording);
		return isAudioRecording;
	}

	private void showToastText(String msg) {
		Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
	}

	private class AudioCaptureAndSendThread implements Runnable {
		public void run() {
			try {
				sendAmrAudio();
			} catch (Exception e) {
				Log.e(TAG, "sendAmrAudio() 出错");
			}
		}

		private void sendAmrAudio() throws Exception {
			Log.d(TAG, "sendAmrAudio");
			DatagramSocket udpSocket = new DatagramSocket();
			DataInputStream dataInputStream = new DataInputStream(receiver.getInputStream());

			skipAmrHead(dataInputStream);

			final int SEND_FRAME_COUNT_ONE_TIME = 10;//每次发送10帧的数据，1帧大约32B
			// AMR格式见博客：http://blog.csdn.net/dinggo/article/details/1966444
			//不同编码格式对应的帧大小，帧头不计算在内
			final int BLOCK_SIZE[] = { 12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0 };

			byte[] sendBuffer = new byte[1024];
			while (isAudioRecording()) {
				Log.d(TAG, "recording...");
				int offset = 0;
				for (int index = 0; index < SEND_FRAME_COUNT_ONE_TIME; ++index) {
					if (!isAudioRecording()) {
						break;
					}
					//1.读取帧头，帧头占一个字节
					dataInputStream.read(sendBuffer, offset, 1);
					//2.根据帧头计算每帧的大小，*0010***,0010表示AMR5.9编码方式
					int blockIndex = (int) (sendBuffer[offset] >> 3) & 0x0F;
					//3.获取对应编码方式的帧长度
					int frameLength = BLOCK_SIZE[blockIndex];
					//4.读取固定长度的帧数据
					readSomeData(sendBuffer, offset + 1, frameLength, dataInputStream);

					//5.更新buffer偏移量
					offset += frameLength + 1;
				}

				//每读取完10帧后发送出去
				udpSend(udpSocket, sendBuffer, offset);
			}
			udpSocket.close();
			dataInputStream.close();
			releaseAll();
		}

		private void skipAmrHead(DataInputStream dataInputStream) {
			Log.d(TAG, "skipAmrHead");
			final byte[] AMR_HEAD = new byte[] { 0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A };
			int result = -1;
			int state = 0;
			try {
				while (-1 != (result = dataInputStream.readByte())) {
					if (AMR_HEAD[0] == result) {
						state = (0 == state) ? 1 : 0;
					} else if (AMR_HEAD[1] == result) {
						state = (1 == state) ? 2 : 0;
					} else if (AMR_HEAD[2] == result) {
						state = (2 == state) ? 3 : 0;
					} else if (AMR_HEAD[3] == result) {
						state = (3 == state) ? 4 : 0;
					} else if (AMR_HEAD[4] == result) {
						state = (4 == state) ? 5 : 0;
					} else if (AMR_HEAD[5] == result) {
						state = (5 == state) ? 6 : 0;
					}

					if (6 == state) {
						break;
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "read mdat error...");
				e.printStackTrace();
			}
		}

		/**
		 * 读取数据
		 * @param buffer 缓存
		 * @param offset 从缓存buffer的offset位置开始写入
		 * @param length 最长读取字节数
		 * @param dataInputStream UDP输入流
		 */
		private void readSomeData(byte[] buffer, int offset, int length, DataInputStream dataInputStream) {
			int numOfRead = -1;
			while (true) {
				try {
					numOfRead = dataInputStream.read(buffer, offset, length);
					if (numOfRead == -1) {
						Log.d(TAG, "amr...no data get wait for data coming.....");
						Thread.sleep(100);
					} else {
						offset += numOfRead;//更新偏移量
						length -= numOfRead;//更新最长读取字节长度
						if (length <= 0) {
							break;
						}
					}
				} catch (Exception e) {
					Log.e(TAG, "amr..error readSomeData");
					break;
				}
			}
		}

		/**
		 * UDP发送数据
		 * @param udpSocket
		 * @param buffer
		 * @param sendLength
		 */
		private void udpSend(DatagramSocket udpSocket, byte[] buffer, int sendLength) {
			try {
				InetAddress ip = InetAddress.getByName(CommonConfig.SERVER_IP_ADDRESS.trim());
				int port = CommonConfig.AUDIO_SERVER_UP_PORT;

				byte[] sendBuffer = new byte[sendLength];
				System.arraycopy(buffer, 0, sendBuffer, 0, sendLength);

				DatagramPacket packet = new DatagramPacket(sendBuffer, sendLength);
				packet.setAddress(ip);
				packet.setPort(port);
				udpSocket.send(packet);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
