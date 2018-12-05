package cn.edu.xmu.zgy.audio.player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;

import cn.edu.xmu.zgy.config.CommonConfig;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.util.Log;

//blog.csdn.net/zgyulongfei
//Email: zgyulongfei@gmail.com

public class AmrAudioPlayer {
	private static final String TAG = "AmrAudioPlayer";

	private static AmrAudioPlayer playerInstance = null;

	private long alreadyReadByteCount = 0;

	private MediaPlayer audioPlayer;
	private Handler handler = new Handler();

	private final String cacheFileName = "audioCacheFile";
	private File cacheFile;
	private int cacheFileCount = 0;

	// 用来记录是否已经从cacheFile中复制数据到另一个cache中
	private boolean hasMovedTheCacheFlag;

	private boolean isPlaying;
	private Activity activity;

	private boolean isChaingCacheToAnother;

	private AmrAudioPlayer() {
	}

	public static AmrAudioPlayer getAmrAudioPlayerInstance() {
		if (playerInstance == null) {
			synchronized (AmrAudioPlayer.class) {
				if (playerInstance == null) {
					playerInstance = new AmrAudioPlayer();
				}
			}
		}
		return playerInstance;
	}

	public void initAmrAudioPlayer(Activity activity) {
		this.activity = activity;
		deleteExistCacheFile();
		initCacheFile();
	}

	private void deleteExistCacheFile() {
		File cacheDir = activity.getCacheDir();
		File[] needDeleteCacheFiles = cacheDir.listFiles();
		for (int index = 0; index < needDeleteCacheFiles.length; ++index) {
			File cache = needDeleteCacheFiles[index];
			if (cache.isFile()) {
				if (cache.getName().contains(cacheFileName.trim())) {
					Log.e(TAG, "delete cache file: " + cache.getName());
					cache.delete();
				}
			}
		}
		needDeleteCacheFiles = null;
	}

	private void initCacheFile() {
		cacheFile = null;
		cacheFile = new File(activity.getCacheDir(), cacheFileName);
	}

	public void start() {
		isPlaying = true;
		isChaingCacheToAnother = false;
		setHasMovedTheCacheToAnotherCache(false);
		new Thread(new NetAudioPlayerThread()).start();
	}

	public void stop() {
		isPlaying = false;
		isChaingCacheToAnother = false;
		setHasMovedTheCacheToAnotherCache(false);
		releaseAudioPlayer();
		deleteExistCacheFile();
		cacheFile = null;
		handler = null;
	}

	private void releaseAudioPlayer() {
		playerInstance = null;
		if (audioPlayer != null) {
			try {
				if (audioPlayer.isPlaying()) {
					audioPlayer.pause();
				}
				audioPlayer.release();
				audioPlayer = null;
			} catch (Exception e) {
			}
		}
	}

	private boolean hasMovedTheCacheToAnotherCache() {
		return hasMovedTheCacheFlag;
	}

	private void setHasMovedTheCacheToAnotherCache(boolean result) {
		hasMovedTheCacheFlag = result;
	}

	private class NetAudioPlayerThread implements Runnable {
		// 从接受数据开始计算，当缓存大于INIT_BUFFER_SIZE时候开始播放
		private final int INIT_AUDIO_BUFFER = 2 * 1024;
		// 剩1秒的时候播放新的缓存的音乐
		private final int CHANGE_CACHE_TIME = 1000;

		public void run() {
			try {
				Socket socket = createSocketConnectToServer();
				receiveNetAudioThenPlay(socket);
			} catch (Exception e) {
				Log.e(TAG, e.getMessage() + "从服务端接受音频失败。。。");
			}
		}

		private Socket createSocketConnectToServer() throws Exception {
			String hostName = CommonConfig.SERVER_IP_ADDRESS;
			InetAddress ipAddress = InetAddress.getByName(hostName);
			int port = CommonConfig.AUDIO_SERVER_DOWN_PORT;
			Socket socket = new Socket(ipAddress, port);
			return socket;
		}

		private void receiveNetAudioThenPlay(Socket socket) throws Exception {
			InputStream inputStream = socket.getInputStream();
			FileOutputStream outputStream = new FileOutputStream(cacheFile);

			final int BUFFER_SIZE = 100 * 1024;// 100kb buffer size
			byte[] buffer = new byte[BUFFER_SIZE];

			// 收集了10*350b了之后才开始更换缓存
			int testTime = 10;
			try {
				alreadyReadByteCount = 0;
				while (isPlaying) {
					int numOfRead = inputStream.read(buffer);
					if (numOfRead <= 0) {
						break;
					}
					alreadyReadByteCount += numOfRead;
					outputStream.write(buffer, 0, numOfRead);
					outputStream.flush();
					try {
						if (testTime++ >= 10) {
							Log.e(TAG, "cacheFile=" + cacheFile.length());
							testWhetherToChangeCache();
							testTime = 0;
						}
					} catch (Exception e) {
						// TODO: handle exception
					}

					// 如果复制了接收网络流的cache，则执行此操作
					if (hasMovedTheCacheToAnotherCache() && !isChaingCacheToAnother) {
						if (outputStream != null) {
							outputStream.close();
							outputStream = null;
						}
						// 将接收网络流的cache删除，然后重0开始存储
						// initCacheFile();
						outputStream = new FileOutputStream(cacheFile);
						setHasMovedTheCacheToAnotherCache(false);
						alreadyReadByteCount = 0;
					}

				}
			} catch (Exception e) {
				errorOperator();
				e.printStackTrace();
				Log.e(TAG, "socket disconnect...:" + e.getMessage());
				throw new Exception("socket disconnect....");
			} finally {
				buffer = null;
				if (socket != null) {
					socket.close();
				}
				if (inputStream != null) {
					inputStream.close();
					inputStream = null;
				}
				if (outputStream != null) {
					outputStream.close();
					outputStream = null;
				}
				stop();
			}
		}

		private void testWhetherToChangeCache() throws Exception {
			if (audioPlayer == null) {
				firstTimeStartPlayer();
			} else {
				changeAnotherCacheWhenEndOfCurrentCache();
			}
		}

		private void firstTimeStartPlayer() throws Exception {
			// 当缓存已经大于INIT_AUDIO_BUFFER则开始播放
			if (alreadyReadByteCount >= INIT_AUDIO_BUFFER) {
				Runnable r = new Runnable() {
					public void run() {
						try {
							File firstCacheFile = createFirstCacheFile();
							// 设置已经从cache中复制数据，然后会删除这个cache
							setHasMovedTheCacheToAnotherCache(true);
							audioPlayer = createAudioPlayer(firstCacheFile);
							audioPlayer.start();
						} catch (Exception e) {
							Log.e(TAG, e.getMessage() + " :in firstTimeStartPlayer() fun");
						} finally {
						}
					}
				};
				handler.post(r);
			}
		}

		private File createFirstCacheFile() throws Exception {
			String firstCacheFileName = cacheFileName + (cacheFileCount++);
			File firstCacheFile = new File(activity.getCacheDir(), firstCacheFileName);
			// 为什么不直接播放cacheFile，而要复制cacheFile到一个新的cache，然后播放此新的cache？
			// 是为了防止潜在的读/写错误，可能在写入cacheFile的时候，
			// MediaPlayer正试图读数据， 这样可以防止死锁的发生。
			moveFile(cacheFile, firstCacheFile);
			return firstCacheFile;

		}

		private void moveFile(File oldFile, File newFile) throws IOException {
			if (!oldFile.exists()) {
				throw new IOException("oldFile is not exists. in moveFile() fun");
			}
			if (oldFile.length() <= 0) {
				throw new IOException("oldFile size = 0. in moveFile() fun");
			}
			BufferedInputStream reader = new BufferedInputStream(new FileInputStream(oldFile));
			BufferedOutputStream writer = new BufferedOutputStream(new FileOutputStream(newFile,
					false));

			final byte[] AMR_HEAD = new byte[] { 0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A };
			writer.write(AMR_HEAD, 0, AMR_HEAD.length);
			writer.flush();

			try {
				byte[] buffer = new byte[1024];
				int numOfRead = 0;
				Log.d(TAG, "POS...newFile.length=" + newFile.length() + "  old=" + oldFile.length());
				while ((numOfRead = reader.read(buffer, 0, buffer.length)) != -1) {
					writer.write(buffer, 0, numOfRead);
					writer.flush();
				}
				Log.d(TAG, "POS..AFTER...newFile.length=" + newFile.length());
			} catch (IOException e) {
				Log.e(TAG, "moveFile error.. in moveFile() fun." + e.getMessage());
				throw new IOException("moveFile error.. in moveFile() fun.");
			} finally {
				if (reader != null) {
					reader.close();
					reader = null;
				}
				if (writer != null) {
					writer.close();
					writer = null;
				}
			}
		}

		private MediaPlayer createAudioPlayer(File audioFile) throws IOException {
			MediaPlayer mPlayer = new MediaPlayer();

			// It appears that for security/permission reasons, it is better to
			// pass
			// a FileDescriptor rather than a direct path to the File.
			// Also I have seen errors such as "PVMFErrNotSupported" and
			// "Prepare failed.: status=0x1" if a file path String is passed to
			// setDataSource(). So unless otherwise noted, we use a
			// FileDescriptor here.
			FileInputStream fis = new FileInputStream(audioFile);
			mPlayer.reset();
			mPlayer.setDataSource(fis.getFD());
			mPlayer.prepare();
			return mPlayer;
		}

		private void changeAnotherCacheWhenEndOfCurrentCache() throws IOException {
			// 检查当前cache剩余时间
			long theRestTime = audioPlayer.getDuration() - audioPlayer.getCurrentPosition();
			Log.e(TAG, "theRestTime=" + theRestTime + "  isChaingCacheToAnother="
					+ isChaingCacheToAnother);
			if (!isChaingCacheToAnother && theRestTime <= CHANGE_CACHE_TIME) {
				isChaingCacheToAnother = true;

				Runnable r = new Runnable() {
					public void run() {
						try {
							File newCacheFile = createNewCache();
							// 设置已经从cache中复制数据，然后会删除这个cache
							setHasMovedTheCacheToAnotherCache(true);
							transferNewCacheToAudioPlayer(newCacheFile);
						} catch (Exception e) {
							Log.e(TAG, e.getMessage()
									+ ":changeAnotherCacheWhenEndOfCurrentCache() fun");
						} finally {
							deleteOldCache();
							isChaingCacheToAnother = false;
						}
					}
				};
				handler.post(r);
			}
		}

		private File createNewCache() throws Exception {
			// 将保存网络数据的cache复制到newCache中进行播放
			String newCacheFileName = cacheFileName + (cacheFileCount++);
			File newCacheFile = new File(activity.getCacheDir(), newCacheFileName);
			Log.e(TAG, "before moveFile............the size=" + cacheFile.length());
			moveFile(cacheFile, newCacheFile);
			return newCacheFile;
		}

		private void transferNewCacheToAudioPlayer(File newCacheFile) throws Exception {
			MediaPlayer oldPlayer = audioPlayer;

			try {
				audioPlayer = createAudioPlayer(newCacheFile);
				audioPlayer.start();
			} catch (Exception e) {
				Log.e(TAG, "filename=" + newCacheFile.getName() + " size=" + newCacheFile.length());
				Log.e(TAG, e.getMessage() + " " + e.getCause() + " error start..in transfanNer..");
			}
			try {
				oldPlayer.pause();
				oldPlayer.reset();
				oldPlayer.release();
			} catch (Exception e) {
				Log.e(TAG, "ERROR release oldPlayer.");
			} finally {
				oldPlayer = null;
			}
		}

		private void deleteOldCache() {
			int oldCacheFileCount = cacheFileCount - 1;
			String oldCacheFileName = cacheFileName + oldCacheFileCount;
			File oldCacheFile = new File(activity.getCacheDir(), oldCacheFileName);
			if (oldCacheFile.exists()) {
				oldCacheFile.delete();
			}
		}

		private void errorOperator() {
		}
	}

}
