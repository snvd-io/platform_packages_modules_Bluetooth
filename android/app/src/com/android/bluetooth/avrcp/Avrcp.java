/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.avrcp;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAvrcp;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.MediaSession.QueueItem;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;

import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/******************************************************************************
 * support Bluetooth AVRCP profile. support metadata, play status, event
 * notifications, address player selection and browse feature implementation.
 ******************************************************************************/

public final class Avrcp {
    private static final boolean DEBUG = true;
    private static final String TAG = "Avrcp";
    private static final String ABSOLUTE_VOLUME_BLACKLIST = "absolute_volume_blacklist";

    private Context mContext;
    private final AudioManager mAudioManager;
    private AvrcpMessageHandler mHandler;
    private MediaSessionManager mMediaSessionManager;
    private MediaController mMediaController;
    private MediaControllerListener mMediaControllerCb;
    private MediaAttributes mMediaAttributes;
    private PackageManager mPackageManager;
    private int mTransportControlFlags;
    private PlaybackState mCurrentPlayState;
    private long mLastStateUpdate;
    private int mPlayStatusChangedNT;
    private int mTrackChangedNT;
    private int mPlayPosChangedNT;
    private long mTracksPlayed;
    private long mSongLengthMs;
    private long mPlaybackIntervalMs;
    private long mLastReportedPosition;
    private long mNextPosMs;
    private long mPrevPosMs;
    private long mSkipStartTime;
    private int mFeatures;
    private int mRemoteVolume;
    private int mLastRemoteVolume;
    private int mInitialRemoteVolume;

    /* Local volume in audio index 0-15 */
    private int mLocalVolume;
    private int mLastLocalVolume;
    private int mAbsVolThreshold;

    private String mAddress;
    private HashMap<Integer, Integer> mVolumeMapping;

    private int mLastDirection;
    private final int mVolumeStep;
    private final int mAudioStreamMax;
    private boolean mVolCmdAdjustInProgress;
    private boolean mVolCmdSetInProgress;
    private int mAbsVolRetryTimes;
    private int mSkipAmount;
    private int mCurrAddrPlayerID;
    private int mCurrBrowsePlayerID;
    private MediaPlayerListRsp mMPLObj;
    private AvrcpMediaRsp mAvrcpMediaRsp;

    /* UID counter to be shared across different files. */
    static short sUIDCounter;

    /* BTRC features */
    public static final int BTRC_FEAT_METADATA = 0x01;
    public static final int BTRC_FEAT_ABSOLUTE_VOLUME = 0x02;
    public static final int BTRC_FEAT_BROWSE = 0x04;

    /* AVRC response codes, from avrc_defs */
    private static final int AVRC_RSP_NOT_IMPL = 8;
    private static final int AVRC_RSP_ACCEPT = 9;
    private static final int AVRC_RSP_REJ = 10;
    private static final int AVRC_RSP_IN_TRANS = 11;
    private static final int AVRC_RSP_IMPL_STBL = 12;
    private static final int AVRC_RSP_CHANGED = 13;
    private static final int AVRC_RSP_INTERIM = 15;

    /* AVRC request commands from Native */
    private static final int MSG_NATIVE_REQ_GET_RC_FEATURES = 1;
    private static final int MSG_NATIVE_REQ_GET_PLAY_STATUS = 2;
    private static final int MSG_NATIVE_REQ_GET_ELEM_ATTRS = 3;
    private static final int MSG_NATIVE_REQ_REGISTER_NOTIFICATION = 4;
    private static final int MSG_NATIVE_REQ_VOLUME_CHANGE = 5;
    private static final int MSG_NATIVE_REQ_GET_FOLDER_ITEMS = 6;
    private static final int MSG_NATIVE_REQ_SET_ADDR_PLAYER = 7;
    private static final int MSG_NATIVE_REQ_SET_BR_PLAYER = 8;
    private static final int MSG_NATIVE_REQ_CHANGE_PATH = 9;
    private static final int MSG_NATIVE_REQ_PLAY_ITEM = 10;
    private static final int MSG_NATIVE_REQ_GET_ITEM_ATTR = 11;
    private static final int MSG_NATIVE_REQ_GET_TOTAL_NUM_OF_ITEMS = 12;
    private static final int MSG_NATIVE_REQ_PASS_THROUGH = 13;

    /* other AVRC messages */
    private static final int MSG_PLAY_INTERVAL_TIMEOUT = 14;
    private static final int MSG_ADJUST_VOLUME = 15;
    private static final int MSG_SET_ABSOLUTE_VOLUME = 16;
    private static final int MSG_ABS_VOL_TIMEOUT = 17;
    private static final int MSG_FAST_FORWARD = 18;
    private static final int MSG_REWIND = 19;
    private static final int MSG_CHANGE_PLAY_POS = 20;
    private static final int MSG_SET_A2DP_AUDIO_STATE = 21;

    private static final int BUTTON_TIMEOUT_TIME = 2000;
    private static final int BASE_SKIP_AMOUNT = 2000;
    private static final int SKIP_PERIOD = 400;
    private static final int SKIP_DOUBLE_INTERVAL = 3000;
    private static final long MAX_MULTIPLIER_VALUE = 128L;
    private static final int CMD_TIMEOUT_DELAY = 2000;
    private static final int MAX_ERROR_RETRY_TIMES = 6;
    private static final int AVRCP_MAX_VOL = 127;
    private static final int AVRCP_BASE_VOLUME_STEP = 1;

    /* Communicates with MediaPlayer to fetch media content */
    private BrowsedMediaPlayer mBrowsedMediaPlayer;

    /* Addressed player */
    private AddressedMediaPlayer mAddressedMediaPlayer;

    /* List of Media player instances, useful for retrieving MediaPlayerList or MediaPlayerInfo */
    private ArrayList<MediaPlayerInfo> mMediaPlayerInfoList;

    /* List of media players which supports browse */
    private ArrayList<BrowsePlayerInfo> mBrowsePlayerInfoList;

    /* Manage browsed players */
    private AvrcpBrowseManager mAvrcpBrowseManager;

    /* Broadcast receiver for device connections intent broadcasts */
    private final BroadcastReceiver mAvrcpReceiver = new AvrcpServiceBroadcastReceiver();
    private final BroadcastReceiver mBootReceiver = new AvrcpServiceBootReceiver();

    static {
        classInitNative();
    }

    private Avrcp(Context context) {
        mMediaAttributes = new MediaAttributes(null);
        mCurrentPlayState = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, -1L, 0.0f).build();
        mPlayStatusChangedNT = AvrcpConstants.NOTIFICATION_TYPE_CHANGED;
        mTrackChangedNT = AvrcpConstants.NOTIFICATION_TYPE_CHANGED;
        mTracksPlayed = 0;
        mLastStateUpdate = -1L;
        mSongLengthMs = 0L;
        mPlaybackIntervalMs = 0L;
        mPlayPosChangedNT = AvrcpConstants.NOTIFICATION_TYPE_CHANGED;
        mLastReportedPosition = -1;
        mNextPosMs = -1;
        mPrevPosMs = -1;
        mFeatures = 0;
        mRemoteVolume = -1;
        mInitialRemoteVolume = -1;
        mLastRemoteVolume = -1;
        mLastDirection = 0;
        mVolCmdAdjustInProgress = false;
        mVolCmdSetInProgress = false;
        mAbsVolRetryTimes = 0;
        mLocalVolume = -1;
        mLastLocalVolume = -1;
        mAbsVolThreshold = 0;
        mVolumeMapping = new HashMap<Integer, Integer>();
        sUIDCounter = AvrcpConstants.DEFAULT_UID_COUNTER;
        mCurrAddrPlayerID = -1;
        mCurrBrowsePlayerID = -1;
        mContext = context;
        mMPLObj = null;
        mAddressedMediaPlayer = null;

        initNative();

        mMediaSessionManager = (MediaSessionManager) context.getSystemService(
            Context.MEDIA_SESSION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioStreamMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mVolumeStep = Math.max(AVRCP_BASE_VOLUME_STEP, AVRCP_MAX_VOL/mAudioStreamMax);

        Resources resources = context.getResources();
        if (resources != null) {
            mAbsVolThreshold = resources.getInteger(R.integer.a2dp_absolute_volume_initial_threshold);
        }

        // Register for package removal intent broadcasts for media button receiver persistence
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        pkgFilter.addDataScheme("package");
        context.registerReceiver(mAvrcpReceiver, pkgFilter);

        IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mBootReceiver, bootFilter);
    }

    private void start() {
        HandlerThread thread = new HandlerThread("BluetoothAvrcpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new AvrcpMessageHandler(looper);
        mMediaControllerCb = new MediaControllerListener();
        mAvrcpMediaRsp = new AvrcpMediaRsp();
        mMediaPlayerInfoList = new ArrayList<MediaPlayerInfo>();
        mBrowsePlayerInfoList = new ArrayList<BrowsePlayerInfo>();
        if (mMediaSessionManager != null) {
            mMediaSessionManager.addOnActiveSessionsChangedListener(mActiveSessionListener, null,
                    mHandler);
        }
        mPackageManager = mContext.getApplicationContext().getPackageManager();

        /* create object to communicate with addressed player */
        mAddressedMediaPlayer = new AddressedMediaPlayer(mAvrcpMediaRsp);

        /* initialize BrowseMananger which manages Browse commands and response */
        mAvrcpBrowseManager = new AvrcpBrowseManager(mContext, mAvrcpMediaRsp);

        // Build the media players list
        buildMediaPlayersList();

        UserManager manager = UserManager.get(mContext);
        if (manager == null || manager.isUserUnlocked()) {
            if (DEBUG) Log.d(TAG, "User already unlocked, initializing player lists");
            // initialize browsable player list and build media player list
            (new BrowsablePlayerListBuilder()).start();
        }
    }

    public static Avrcp make(Context context) {
        if (DEBUG) Log.v(TAG, "make");
        Avrcp ar = new Avrcp(context);
        ar.start();
        return ar;
    }

    public void doQuit() {
        if (DEBUG) Log.d(TAG, "doQuit");
        mHandler.removeCallbacksAndMessages(null);
        Looper looper = mHandler.getLooper();
        if (looper != null) {
            looper.quit();
        }

        unregOldMediaControllerCb();
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveSessionListener);

        mHandler = null;
        mMPLObj = null;
        mContext.unregisterReceiver(mAvrcpReceiver);
        mContext.unregisterReceiver(mBootReceiver);

        mAddressedMediaPlayer.cleanup();
        mAvrcpBrowseManager.cleanup();
    }

    public void cleanup() {
        if (DEBUG) Log.d(TAG, "cleanup");
        cleanupNative();
        if (mVolumeMapping != null)
            mVolumeMapping.clear();
    }

    private class MediaControllerListener extends MediaController.Callback {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            Log.v(TAG, "MediaController metadata changed");
            updateMetadata(metadata);
        }

        @Override
        public synchronized void onPlaybackStateChanged(PlaybackState state) {
            Log.v(TAG, "MediaController playback changed: " + state.toString());

            updatePlaybackState(state);

            if (DEBUG) Log.v(TAG, "onPlaybackStateChanged: state=" + state.getState());
            byte stateBytes = (byte) convertPlayStateToBytes(state.getState());

            /* updating play status in global media player list */
            if (!isCurrentMediaPlayerListEmpty() && isIdValid(mCurrAddrPlayerID)) {
                try {
                    mMediaPlayerInfoList.get(mCurrAddrPlayerID - 1).setPlayStatus(stateBytes);
                } catch (IndexOutOfBoundsException e) {
                    Log.i(TAG, "onPlaybackStateChanged: list size = " + getPlayerListSize() +
                            ", mCurrAddrPlayerID = " + mCurrAddrPlayerID);
                    e.printStackTrace();
                }
            }

        }

        @Override
        public void onSessionDestroyed() {
            Log.v(TAG, "MediaController session destroyed");
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            if (queue == null) {
                Log.v(TAG, "onQueueChanged: received null queue");
                return;
            }

            Log.v(TAG, "onQueueChanged: NowPlaying list changed, Queue Size = "+ queue.size());
            mAddressedMediaPlayer.updateNowPlayingList(queue);

            /* sent notification to remote for NowPlayingList changed */
            if(!registerNotificationRspNowPlayingChangedNative(
                    AvrcpConstants.NOTIFICATION_TYPE_CHANGED)){
                Log.e(TAG, "onQueueChanged-registerNotificationRspNowPlayingChangedNative failed");
            }
        }
    }

    /** Handles Avrcp messages. */
    private final class AvrcpMessageHandler extends Handler {
        private AvrcpMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (DEBUG) Log.v(TAG, "AvrcpMessageHandler: received message=" + msg.what);

            switch (msg.what) {
            case MSG_NATIVE_REQ_GET_RC_FEATURES:
            {
                String address = (String) msg.obj;
                if (DEBUG) Log.v(TAG, "MSG_NATIVE_REQ_GET_RC_FEATURES: address="+address+
                        ", features="+msg.arg1);
                mFeatures = msg.arg1;
                mFeatures = modifyRcFeatureFromBlacklist(mFeatures, address);
                mAudioManager.avrcpSupportsAbsoluteVolume(address, isAbsoluteVolumeSupported());
                mLastLocalVolume = -1;
                mRemoteVolume = -1;
                mLocalVolume = -1;
                mInitialRemoteVolume = -1;
                mAddress = address;
                if (mVolumeMapping != null)
                    mVolumeMapping.clear();
                break;
            }

            case MSG_NATIVE_REQ_GET_PLAY_STATUS:
            {
                byte[] address = (byte[]) msg.obj;
                if (DEBUG) Log.v(TAG, "MSG_NATIVE_REQ_GET_PLAY_STATUS");
                getPlayStatusRspNative(address, convertPlayStateToPlayStatus(mCurrentPlayState),
                        (int) mSongLengthMs, (int) getPlayPosition());
                break;
            }

            case MSG_NATIVE_REQ_GET_ELEM_ATTRS:
            {
                String[] textArray;
                AvrcpCmd.ElementAttrCmd elem = (AvrcpCmd.ElementAttrCmd) msg.obj;
                byte numAttr = elem.mNumAttr;
                int[] attrIds = elem.mAttrIDs;
                if (DEBUG) Log.v(TAG, "MSG_NATIVE_REQ_GET_ELEM_ATTRS:numAttr=" + numAttr);
                textArray = new String[numAttr];
                for (int i = 0; i < numAttr; ++i) {
                    textArray[i] = mMediaAttributes.getString(attrIds[i]);
                    Log.v(TAG, "getAttributeString:attrId=" + attrIds[i] +
                            " str=" + textArray[i]);
                }
                byte[] bdaddr = elem.mAddress;
                getElementAttrRspNative(bdaddr, numAttr, attrIds, textArray);
                break;
            }

            case MSG_NATIVE_REQ_REGISTER_NOTIFICATION:
                if (DEBUG) Log.v(TAG, "MSG_NATIVE_REQ_REGISTER_NOTIFICATION:event=" + msg.arg1 +
                        " param=" + msg.arg2);
                processRegisterNotification((byte[]) msg.obj, msg.arg1, msg.arg2);
                break;

            case MSG_PLAY_INTERVAL_TIMEOUT:
                if (DEBUG) Log.v(TAG, "MSG_PLAY_INTERVAL_TIMEOUT");
                sendPlayPosNotificationRsp(false);
                break;

            case MSG_NATIVE_REQ_VOLUME_CHANGE:
                if (!isAbsoluteVolumeSupported()) {
                    if (DEBUG) Log.v(TAG, "ignore MSG_NATIVE_REQ_VOLUME_CHANGE");
                    break;
                }

                if (DEBUG) Log.v(TAG, "MSG_NATIVE_REQ_VOLUME_CHANGE: volume=" + ((byte) msg.arg1 & 0x7f)
                        + " ctype=" + msg.arg2);

                boolean volAdj = false;
                if (msg.arg2 == AVRC_RSP_ACCEPT || msg.arg2 == AVRC_RSP_REJ) {
                    if (mVolCmdAdjustInProgress == false && mVolCmdSetInProgress == false) {
                        Log.e(TAG, "Unsolicited response, ignored");
                        break;
                    }
                    removeMessages(MSG_ABS_VOL_TIMEOUT);

                    volAdj = mVolCmdAdjustInProgress;
                    mVolCmdAdjustInProgress = false;
                    mVolCmdSetInProgress = false;
                    mAbsVolRetryTimes = 0;
                }

                byte absVol = (byte) ((byte) msg.arg1 & 0x7f); // discard MSB as it is RFD
                // convert remote volume to local volume
                int volIndex = convertToAudioStreamVolume(absVol);
                if (mInitialRemoteVolume == -1) {
                    mInitialRemoteVolume = absVol;
                    if (mAbsVolThreshold > 0 && mAbsVolThreshold < mAudioStreamMax && volIndex > mAbsVolThreshold) {
                        if (DEBUG) Log.v(TAG, "remote inital volume too high " + volIndex + ">" + mAbsVolThreshold);
                        Message msg1 = mHandler.obtainMessage(MSG_SET_ABSOLUTE_VOLUME, mAbsVolThreshold , 0);
                        mHandler.sendMessage(msg1);
                        mRemoteVolume = absVol;
                        mLocalVolume = volIndex;
                        break;
                    }
                }

                if (mLocalVolume != volIndex && (msg.arg2 == AVRC_RSP_ACCEPT ||
                                                 msg.arg2 == AVRC_RSP_CHANGED ||
                                                 msg.arg2 == AVRC_RSP_INTERIM)) {
                    /* If the volume has successfully changed */
                    mLocalVolume = volIndex;
                    if (mLastLocalVolume != -1 && msg.arg2 == AVRC_RSP_ACCEPT) {
                        if (mLastLocalVolume != volIndex) {
                            /* remote volume changed more than requested due to
                             * local and remote has different volume steps */
                            if (DEBUG) Log.d(TAG, "Remote returned volume does not match desired volume "
                                    + mLastLocalVolume + " vs " + volIndex);
                            mLastLocalVolume = mLocalVolume;
                        }
                    }
                    // remember the remote volume value, as it's the one supported by remote
                    if (volAdj) {
                        synchronized (mVolumeMapping) {
                            mVolumeMapping.put(volIndex, (int) absVol);
                            if (DEBUG) Log.v(TAG, "remember volume mapping " +volIndex+ "-"+absVol);
                        }
                    }

                    notifyVolumeChanged(mLocalVolume);
                    mRemoteVolume = absVol;
                    long pecentVolChanged = ((long) absVol * 100) / 0x7f;
                    Log.e(TAG, "percent volume changed: " + pecentVolChanged + "%");
                } else if (msg.arg2 == AVRC_RSP_REJ) {
                    Log.e(TAG, "setAbsoluteVolume call rejected");
                } else if (volAdj && mLastRemoteVolume > 0 && mLastRemoteVolume < AVRCP_MAX_VOL &&
                        mLocalVolume == volIndex &&
                        (msg.arg2 == AVRC_RSP_ACCEPT)) {
                    /* oops, the volume is still same, remote does not like the value
                     * retry a volume one step up/down */
                    if (DEBUG) Log.d(TAG, "Remote device didn't tune volume, let's try one more step.");
                    int retry_volume = Math.min(AVRCP_MAX_VOL,
                            Math.max(0, mLastRemoteVolume + mLastDirection));
                    if (setVolumeNative(retry_volume)) {
                        mLastRemoteVolume = retry_volume;
                        sendMessageDelayed(obtainMessage(MSG_ABS_VOL_TIMEOUT), CMD_TIMEOUT_DELAY);
                        mVolCmdAdjustInProgress = true;
                    }
                }
                break;

            case MSG_ADJUST_VOLUME:
                if (!isAbsoluteVolumeSupported()) {
                    if (DEBUG) Log.v(TAG, "ignore MSG_ADJUST_VOLUME");
                    break;
                }

                if (DEBUG) Log.d(TAG, "MSG_ADJUST_VOLUME: direction=" + msg.arg1);

                if (mVolCmdAdjustInProgress || mVolCmdSetInProgress) {
                    if (DEBUG) Log.w(TAG, "There is already a volume command in progress.");
                    break;
                }

                // Remote device didn't set initial volume. Let's black list it
                if (mInitialRemoteVolume == -1) {
                    Log.d(TAG, "remote " + mAddress + " never tell us initial volume, black list it.");
                    blackListCurrentDevice();
                    break;
                }

                // Wait on verification on volume from device, before changing the volume.
                if (mRemoteVolume != -1 && (msg.arg1 == -1 || msg.arg1 == 1)) {
                    int setVol = -1;
                    int targetVolIndex = -1;
                    if (mLocalVolume == 0 && msg.arg1 == -1) {
                        if (DEBUG) Log.w(TAG, "No need to Vol down from 0.");
                        break;
                    }
                    if (mLocalVolume == mAudioStreamMax && msg.arg1 == 1) {
                        if (DEBUG) Log.w(TAG, "No need to Vol up from max.");
                        break;
                    }

                    targetVolIndex = mLocalVolume + msg.arg1;
                    if (DEBUG) Log.d(TAG, "Adjusting volume to  " + targetVolIndex);

                    Integer i;
                    synchronized (mVolumeMapping) {
                        i = mVolumeMapping.get(targetVolIndex);
                    }

                    if (i != null) {
                        /* if we already know this volume mapping, use it */
                        setVol = i.byteValue();
                        if (setVol == mRemoteVolume) {
                            if (DEBUG) Log.d(TAG, "got same volume from mapping for " + targetVolIndex + ", ignore.");
                            setVol = -1;
                        }
                        if (DEBUG) Log.d(TAG, "set volume from mapping " + targetVolIndex + "-" + setVol);
                    }

                    if (setVol == -1) {
                        /* otherwise use phone steps */
                        setVol = Math.min(AVRCP_MAX_VOL,
                                convertToAvrcpVolume(Math.max(0, targetVolIndex)));
                        if (DEBUG) Log.d(TAG, "set volume from local volume "+ targetVolIndex+"-"+ setVol);
                    }

                    if (setVolumeNative(setVol)) {
                        sendMessageDelayed(obtainMessage(MSG_ABS_VOL_TIMEOUT), CMD_TIMEOUT_DELAY);
                        mVolCmdAdjustInProgress = true;
                        mLastDirection = msg.arg1;
                        mLastRemoteVolume = setVol;
                        mLastLocalVolume = targetVolIndex;
                    } else {
                         if (DEBUG) Log.d(TAG, "setVolumeNative failed");
                    }
                } else {
                    Log.e(TAG, "Unknown direction in MSG_ADJUST_VOLUME");
                }
                break;

            case MSG_SET_ABSOLUTE_VOLUME:
                if (!isAbsoluteVolumeSupported()) {
                    if (DEBUG) Log.v(TAG, "ignore MSG_SET_ABSOLUTE_VOLUME");
                    break;
                }

                if (DEBUG) Log.v(TAG, "MSG_SET_ABSOLUTE_VOLUME");

                if (mVolCmdSetInProgress || mVolCmdAdjustInProgress) {
                    if (DEBUG) Log.w(TAG, "There is already a volume command in progress.");
                    break;
                }

                // Remote device didn't set initial volume. Let's black list it
                if (mInitialRemoteVolume == -1) {
                    if (DEBUG) Log.d(TAG, "remote " + mAddress + " never tell us initial volume, black list it.");
                    blackListCurrentDevice();
                    break;
                }

                int avrcpVolume = convertToAvrcpVolume(msg.arg1);
                avrcpVolume = Math.min(AVRCP_MAX_VOL, Math.max(0, avrcpVolume));
                if (DEBUG) Log.d(TAG, "Setting volume to " + msg.arg1 + "-" + avrcpVolume);
                if (setVolumeNative(avrcpVolume)) {
                    sendMessageDelayed(obtainMessage(MSG_ABS_VOL_TIMEOUT), CMD_TIMEOUT_DELAY);
                    mVolCmdSetInProgress = true;
                    mLastRemoteVolume = avrcpVolume;
                    mLastLocalVolume = msg.arg1;
                } else {
                     if (DEBUG) Log.d(TAG, "setVolumeNative failed");
                }
                break;

            case MSG_ABS_VOL_TIMEOUT:
                if (DEBUG) Log.v(TAG, "MSG_ABS_VOL_TIMEOUT: Volume change cmd timed out.");
                mVolCmdAdjustInProgress = false;
                mVolCmdSetInProgress = false;
                if (mAbsVolRetryTimes >= MAX_ERROR_RETRY_TIMES) {
                    mAbsVolRetryTimes = 0;
                    /* too many volume change failures, black list the device */
                    blackListCurrentDevice();
                } else {
                    mAbsVolRetryTimes += 1;
                    if (setVolumeNative(mLastRemoteVolume)) {
                        sendMessageDelayed(obtainMessage(MSG_ABS_VOL_TIMEOUT), CMD_TIMEOUT_DELAY);
                        mVolCmdSetInProgress = true;
                    }
                }
                break;

            case MSG_FAST_FORWARD:
            case MSG_REWIND:
                if (msg.what == MSG_FAST_FORWARD) {
                    if ((mCurrentPlayState.getActions() &
                            PlaybackState.ACTION_FAST_FORWARD) != 0) {
                        int keyState = msg.arg1 == AvrcpConstants.KEY_STATE_PRESS ?
                                KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                        KeyEvent keyEvent =
                                new KeyEvent(keyState, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                        mMediaController.dispatchMediaButtonEvent(keyEvent);
                        break;
                    }
                } else if ((mCurrentPlayState.getActions() &
                            PlaybackState.ACTION_REWIND) != 0) {
                    int keyState = msg.arg1 == AvrcpConstants.KEY_STATE_PRESS ?
                            KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                    KeyEvent keyEvent =
                            new KeyEvent(keyState, KeyEvent.KEYCODE_MEDIA_REWIND);
                    mMediaController.dispatchMediaButtonEvent(keyEvent);
                    break;
                }

                int skipAmount;
                int playStatus;
                if (msg.what == MSG_FAST_FORWARD) {
                    if (DEBUG) Log.v(TAG, "MSG_FAST_FORWARD");
                    removeMessages(MSG_FAST_FORWARD);
                    skipAmount = BASE_SKIP_AMOUNT;
                    playStatus = PLAYSTATUS_FWD_SEEK;
                } else {
                    if (DEBUG) Log.v(TAG, "MSG_REWIND");
                    removeMessages(MSG_REWIND);
                    skipAmount = -BASE_SKIP_AMOUNT;
                    playStatus = PLAYSTATUS_REV_SEEK;
                }

                if (hasMessages(MSG_CHANGE_PLAY_POS) &&
                        (skipAmount != mSkipAmount)) {
                    Log.w(TAG, "missing release button event:" + mSkipAmount);
                }

                if ((!hasMessages(MSG_CHANGE_PLAY_POS)) ||
                        (skipAmount != mSkipAmount)) {
                    mSkipStartTime = SystemClock.elapsedRealtime();
                }

                removeMessages(MSG_CHANGE_PLAY_POS);
                if (msg.arg1 == AvrcpConstants.KEY_STATE_PRESS) {
                    mSkipAmount = skipAmount;
                    changePositionBy(mSkipAmount * getSkipMultiplier());
                    Message posMsg = obtainMessage(MSG_CHANGE_PLAY_POS);
                    posMsg.arg1 = 1;
                    sendMessageDelayed(posMsg, SKIP_PERIOD);
                }

                registerNotificationRspPlayStatusNative(
                        AvrcpConstants.NOTIFICATION_TYPE_CHANGED, playStatus);

                break;

            case MSG_CHANGE_PLAY_POS:
                if (DEBUG) Log.v(TAG, "MSG_CHANGE_PLAY_POS:" + msg.arg1);
                changePositionBy(mSkipAmount * getSkipMultiplier());
                if (msg.arg1 * SKIP_PERIOD < BUTTON_TIMEOUT_TIME) {
                    Message posMsg = obtainMessage(MSG_CHANGE_PLAY_POS);
                    posMsg.arg1 = msg.arg1 + 1;
                    sendMessageDelayed(posMsg, SKIP_PERIOD);
                }
                break;

            case MSG_SET_A2DP_AUDIO_STATE:
                if (DEBUG) Log.v(TAG, "MSG_SET_A2DP_AUDIO_STATE:" + msg.arg1);
                updateA2dpAudioState(msg.arg1);
                break;

            case MSG_NATIVE_REQ_GET_FOLDER_ITEMS: {
                AvrcpCmd.FolderItemsCmd folderObj = (AvrcpCmd.FolderItemsCmd) msg.obj;
                switch (folderObj.mScope) {
                    case AvrcpConstants.BTRC_SCOPE_PLAYER_LIST:
                        handleMediaPlayerListRsp(folderObj);
                        break;
                    case AvrcpConstants.BTRC_SCOPE_FILE_SYSTEM:
                    case AvrcpConstants.BTRC_SCOPE_NOW_PLAYING:
                        handleGetFolderItemBrowseResponse(folderObj, folderObj.mAddress);
                        break;
                    default:
                        Log.e(TAG, "unknown scope for getfolderitems. scope = "
                                + folderObj.mScope);
                        getFolderItemsRspNative(folderObj.mAddress,
                                AvrcpConstants.RSP_INV_SCOPE, (short) 0, (byte) 0, 0,
                                null, null, null, null, null, null, null, null);
                }
                break;
            }

            case MSG_NATIVE_REQ_SET_ADDR_PLAYER:
                // object is bdaddr, argument 1 is the selected player id
                setAddressedPlayer((byte[]) msg.obj, msg.arg1);
                break;

            case MSG_NATIVE_REQ_GET_ITEM_ATTR:
                // msg object contains the item attribute object
                handleGetItemAttr((AvrcpCmd.ItemAttrCmd) msg.obj);
                break;

            case MSG_NATIVE_REQ_SET_BR_PLAYER:
                // argument 1 is the selected player id
                setBrowsedPlayer((byte[]) msg.obj, msg.arg1);
                break;

            case MSG_NATIVE_REQ_CHANGE_PATH:
            {
                Bundle data = msg.getData();
                byte[] bdaddr = data.getByteArray("BdAddress");
                byte[] folderUid = data.getByteArray("folderUid");
                byte direction = data.getByte("direction");
                if (mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr) != null) {
                        mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr).changePath(folderUid,
                        direction);
                } else {
                    Log.e(TAG, "Remote requesting change path before setbrowsedplayer");
                    changePathRspNative(bdaddr, AvrcpConstants.RSP_BAD_CMD, 0);
                }
                break;
            }

            case MSG_NATIVE_REQ_PLAY_ITEM:
            {
                Bundle data = msg.getData();
                byte[] bdaddr = data.getByteArray("BdAddress");
                byte[] uid = data.getByteArray("uid");
                byte scope = data.getByte("scope");
                handlePlayItemResponse(bdaddr, uid, scope);
                break;
            }

            case MSG_NATIVE_REQ_GET_TOTAL_NUM_OF_ITEMS:
                // argument 1 is scope, object is bdaddr
                handleGetTotalNumOfItemsResponse((byte[]) msg.obj, (byte) msg.arg1);
                break;

            case MSG_NATIVE_REQ_PASS_THROUGH:
                // argument 1 is id, argument 2 is keyState, object is bdaddr
                mAddressedMediaPlayer.handlePassthroughCmd(msg.arg1, msg.arg2, (byte[]) msg.obj,
                        mMediaController);
                break;

            default:
                Log.e(TAG, "unknown message! msg.what=" + msg.what);
                break;
            }
        }
    }

    private void updateA2dpAudioState(int state) {
        boolean isPlaying = (state == BluetoothA2dp.STATE_PLAYING);
        if (isPlaying != isPlayingState(mCurrentPlayState)) {
            /* if a2dp is streaming, check to make sure music is active */
            if (isPlaying && !mAudioManager.isMusicActive())
                return;
            PlaybackState.Builder builder = new PlaybackState.Builder();
            if (isPlaying) {
                builder.setState(PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
            } else {
                builder.setState(PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f);
            }
            updatePlaybackState(builder.build());
        }
    }

    private void updatePlaybackState(PlaybackState state) {
        if (state == null) {
          state = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f).build();
        }

        int oldPlayStatus = convertPlayStateToPlayStatus(mCurrentPlayState);
        int newPlayStatus = convertPlayStateToPlayStatus(state);

        if (DEBUG) {
            Log.v(TAG, "updatePlaybackState (" + mPlayStatusChangedNT + "): "+
                    "old=" + mCurrentPlayState + "(" + oldPlayStatus + "), "+
                    "new=" + state + "(" + newPlayStatus + ")");
        }

        mCurrentPlayState = state;
        mLastStateUpdate = SystemClock.elapsedRealtime();

        sendPlayPosNotificationRsp(false);

        if (mPlayStatusChangedNT == AvrcpConstants.NOTIFICATION_TYPE_INTERIM &&
                (oldPlayStatus != newPlayStatus)) {
            mPlayStatusChangedNT = AvrcpConstants.NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspPlayStatusNative(mPlayStatusChangedNT, newPlayStatus);
        }
    }

    private void updateTransportControls(int transportControlFlags) {
        mTransportControlFlags = transportControlFlags;
    }

    class MediaAttributes {
        private boolean exists;
        private String title;
        private String artistName;
        private String albumName;
        private String mediaNumber;
        private String mediaTotalNumber;
        private String genre;
        private String playingTimeMs;

        private static final int ATTR_TITLE = 1;
        private static final int ATTR_ARTIST_NAME = 2;
        private static final int ATTR_ALBUM_NAME = 3;
        private static final int ATTR_MEDIA_NUMBER = 4;
        private static final int ATTR_MEDIA_TOTAL_NUMBER = 5;
        private static final int ATTR_GENRE = 6;
        private static final int ATTR_PLAYING_TIME_MS = 7;


        public MediaAttributes(MediaMetadata data) {
            exists = data != null;
            if (!exists)
                return;

            artistName = stringOrBlank(data.getString(MediaMetadata.METADATA_KEY_ARTIST));
            albumName = stringOrBlank(data.getString(MediaMetadata.METADATA_KEY_ALBUM));
            mediaNumber = longStringOrBlank(data.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER));
            mediaTotalNumber = longStringOrBlank(data.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS));
            genre = stringOrBlank(data.getString(MediaMetadata.METADATA_KEY_GENRE));
            playingTimeMs = longStringOrBlank(data.getLong(MediaMetadata.METADATA_KEY_DURATION));

            // Try harder for the title.
            title = data.getString(MediaMetadata.METADATA_KEY_TITLE);

            if (title == null) {
                MediaDescription desc = data.getDescription();
                if (desc != null) {
                    CharSequence val = desc.getDescription();
                    if (val != null)
                        title = val.toString();
                }
            }

            if (title == null)
                title = new String();
        }

        public boolean equals(MediaAttributes other) {
            if (other == null)
                return false;

            if (exists != other.exists)
                return false;

            if (exists == false)
                return true;

            return (title.equals(other.title)) &&
                (artistName.equals(other.artistName)) &&
                (albumName.equals(other.albumName)) &&
                (mediaNumber.equals(other.mediaNumber)) &&
                (mediaTotalNumber.equals(other.mediaTotalNumber)) &&
                (genre.equals(other.genre)) &&
                (playingTimeMs.equals(other.playingTimeMs));
        }

        public String getString(int attrId) {
            if (!exists)
                return new String();

            switch (attrId) {
                case ATTR_TITLE:
                    return title;
                case ATTR_ARTIST_NAME:
                    return artistName;
                case ATTR_ALBUM_NAME:
                    return albumName;
                case ATTR_MEDIA_NUMBER:
                    return mediaNumber;
                case ATTR_MEDIA_TOTAL_NUMBER:
                    return mediaTotalNumber;
                case ATTR_GENRE:
                    return genre;
                case ATTR_PLAYING_TIME_MS:
                    return playingTimeMs;
                default:
                    return new String();
            }
        }

        private String stringOrBlank(String s) {
            return s == null ? new String() : s;
        }

        private String longStringOrBlank(Long s) {
            return s == null ? new String() : s.toString();
        }

        public String toString() {
            if (!exists) {
                return "[MediaAttributes: none]";
            }

            return "[MediaAttributes: " + title + " - " + albumName + " by "
                    + artistName + " (" + mediaNumber + "/" + mediaTotalNumber + ") "
                    + genre + "]";
        }
    }

    private void updateMetadata(MediaMetadata data) {
        MediaAttributes oldAttributes = mMediaAttributes;
        mMediaAttributes = new MediaAttributes(data);
        if (data == null) {
            mSongLengthMs = 0L;
        } else {
            mSongLengthMs = data.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }

        if (!oldAttributes.equals(mMediaAttributes)) {
            Log.v(TAG, "MediaAttributes Changed to " + mMediaAttributes.toString());
            mTracksPlayed++;

            if (mTrackChangedNT == AvrcpConstants.NOTIFICATION_TYPE_INTERIM) {
                mTrackChangedNT = AvrcpConstants.NOTIFICATION_TYPE_CHANGED;
                sendTrackChangedRsp();
            }
        } else {
            Log.v(TAG, "Updated " + mMediaAttributes.toString() + " but no change!");
        }

        // Update the play state, which sends play state and play position
        // notifications if needed.
        if (mMediaController != null) {
            updatePlaybackState(mMediaController.getPlaybackState());
        } else {
            updatePlaybackState(null);
        }
    }

    private void getRcFeaturesRequestFromNative(byte[] address, int features) {
        if (DEBUG) Log.v(TAG, "getRcFeaturesRequestFromNative: address=" + address.toString());
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_GET_RC_FEATURES, features, 0,
                Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void getPlayStatusRequestFromNative(byte[] address) {
        if (DEBUG) Log.v(TAG, "getPlayStatusRequestFromNative: address" + address.toString());
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_GET_PLAY_STATUS);
        msg.obj = address;
        mHandler.sendMessage(msg);
    }

    private void getElementAttrRequestFromNative(byte[] address,byte numAttr, int[] attrs) {
        if (DEBUG) Log.v(TAG, "getElementAttrRequestFromNative: numAttr=" + numAttr);
        AvrcpCmd avrcpCmdobj = new AvrcpCmd();
        AvrcpCmd.ElementAttrCmd elemAttr = avrcpCmdobj.new ElementAttrCmd(address, numAttr, attrs);
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_GET_ELEM_ATTRS);
        msg.obj = elemAttr;
        mHandler.sendMessage(msg);
    }

    private void registerNotificationRequestFromNative(byte[] address,int eventId, int param) {
        if (DEBUG) Log.v(TAG, "registerNotificationRequestFromNative: eventId=" + eventId);
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_REGISTER_NOTIFICATION, eventId, param);
        msg.obj = address;
        mHandler.sendMessage(msg);
    }

    private void processRegisterNotification(byte[] address, int eventId, int param) {
        switch (eventId) {
            case EVT_PLAY_STATUS_CHANGED:
                mPlayStatusChangedNT = AvrcpConstants.NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspPlayStatusNative(mPlayStatusChangedNT,
                        convertPlayStateToPlayStatus(mCurrentPlayState));
                break;

            case EVT_TRACK_CHANGED:
                Log.v(TAG, "Track changed notification enabled");
                mTrackChangedNT = AvrcpConstants.NOTIFICATION_TYPE_INTERIM;
                sendTrackChangedRsp();
                break;

            case EVT_PLAY_POS_CHANGED:
                mPlayPosChangedNT = AvrcpConstants.NOTIFICATION_TYPE_INTERIM;
                mPlaybackIntervalMs = (long) param * 1000L;
                sendPlayPosNotificationRsp(true);
                break;

            case EVT_AVBL_PLAYERS_CHANGED:
                /* Notify remote available players changed */
                if (DEBUG) Log.d (TAG, "sending availablePlayersChanged to remote ");
                registerNotificationRspAvalPlayerChangedNative(
                        AvrcpConstants.NOTIFICATION_TYPE_INTERIM);
                break;

            case EVT_ADDR_PLAYER_CHANGED:
                /* Notify remote addressed players changed */
                if (DEBUG) Log.d (TAG, "sending addressedPlayersChanged to remote ");
                registerNotificationRspAddrPlayerChangedNative(
                        AvrcpConstants.NOTIFICATION_TYPE_INTERIM,
                        mCurrAddrPlayerID, sUIDCounter);
                break;

            case EVENT_UIDS_CHANGED:
                if (DEBUG) Log.d(TAG, "sending UIDs changed to remote");
                registerNotificationRspUIDsChangedNative(
                        AvrcpConstants.NOTIFICATION_TYPE_INTERIM, sUIDCounter);
                break;

            case EVENT_NOW_PLAYING_CONTENT_CHANGED:
                if (DEBUG) Log.d(TAG, "sending NowPlayingList changed to remote");
                /* send interim response to remote device */
                if (!registerNotificationRspNowPlayingChangedNative(
                        AvrcpConstants.NOTIFICATION_TYPE_INTERIM)) {
                    Log.e(TAG, "EVENT_NOW_PLAYING_CONTENT_CHANGED: " +
                            "registerNotificationRspNowPlayingChangedNative for Interim rsp failed!");
                }
                break;
        }
    }

    private void handlePassthroughCmdRequestFromNative(byte[] address, int id, int keyState) {
        switch (id) {
            case BluetoothAvrcp.PASSTHROUGH_ID_REWIND:
                rewind(address, keyState);
                return;
            case BluetoothAvrcp.PASSTHROUGH_ID_FAST_FOR:
                fastForward(address, keyState);
                return;
        }

        /* For all other pass through commands other than fast forward and backward
         * (like play, pause, next, previous, stop, etc.); sending to current addressed player.
         */
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_PASS_THROUGH, id, keyState, address);
        mHandler.sendMessage(msg);
    }

    private void fastForward(byte[] address, int keyState) {
        Message msg = mHandler.obtainMessage(MSG_FAST_FORWARD, keyState, 0);
        Bundle data = new Bundle();
        data.putByteArray("BdAddress" , address);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    private void rewind(byte[] address, int keyState) {
        Message msg = mHandler.obtainMessage(MSG_REWIND, keyState, 0);
        Bundle data = new Bundle();
        data.putByteArray("BdAddress" , address);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    private void changePositionBy(long amount) {
        long currentPosMs = getPlayPosition();
        if (currentPosMs == -1L) return;
        long newPosMs = Math.max(0L, currentPosMs + amount);
        mMediaController.getTransportControls().seekTo(newPosMs);
    }

    private int getSkipMultiplier() {
        long currentTime = SystemClock.elapsedRealtime();
        long multi = (long) Math.pow(2, (currentTime - mSkipStartTime)/SKIP_DOUBLE_INTERVAL);
        return (int) Math.min(MAX_MULTIPLIER_VALUE, multi);
    }

    private void sendTrackChangedRsp() {
        // for players which does not support Browse or when no track is currently selected
        if (!isBrowseSupported(getCurrentAddrPlayer())) {
            trackChangeRspForBrowseUnsupported();
        } else {
            // for players which support browsing
            mAddressedMediaPlayer.sendTrackChangeWithId(mTrackChangedNT, mMediaController);
        }
    }

    private void trackChangeRspForBrowseUnsupported() {
        byte[] track = AvrcpConstants.TRACK_IS_SELECTED;
        if (mTrackChangedNT == AvrcpConstants.NOTIFICATION_TYPE_INTERIM
                && !mMediaAttributes.exists) {
            track = AvrcpConstants.NO_TRACK_SELECTED;
        }
        registerNotificationRspTrackChangeNative(mTrackChangedNT, track);
    }

    private long getPlayPosition() {
        if (mCurrentPlayState == null) {
            return -1L;
        }

        if (mCurrentPlayState.getPosition() == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            return -1L;
        }

        if (isPlayingState(mCurrentPlayState)) {
            return SystemClock.elapsedRealtime() - mLastStateUpdate + mCurrentPlayState.getPosition();
        }

        return mCurrentPlayState.getPosition();
    }

    private int convertPlayStateToPlayStatus(PlaybackState state) {
        int playStatus = PLAYSTATUS_ERROR;
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
                playStatus = PLAYSTATUS_PLAYING;
                break;

            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE:
                playStatus = PLAYSTATUS_STOPPED;
                break;

            case PlaybackState.STATE_PAUSED:
                playStatus = PLAYSTATUS_PAUSED;
                break;

            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                playStatus = PLAYSTATUS_FWD_SEEK;
                break;

            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                playStatus = PLAYSTATUS_REV_SEEK;
                break;

            case PlaybackState.STATE_ERROR:
                playStatus = PLAYSTATUS_ERROR;
                break;

        }
        return playStatus;
    }

    private boolean isPlayingState(PlaybackState state) {
        return (state.getState() == PlaybackState.STATE_PLAYING) ||
                (state.getState() == PlaybackState.STATE_BUFFERING);
    }

    /**
     * Sends a play position notification, or schedules one to be
     * sent later at an appropriate time. If |requested| is true,
     * does both because this was called in reponse to a request from the
     * TG.
     */
    private void sendPlayPosNotificationRsp(boolean requested) {
        if (!requested && mPlayPosChangedNT != AvrcpConstants.NOTIFICATION_TYPE_INTERIM) {
            if (DEBUG) Log.d(TAG, "sendPlayPosNotificationRsp: Not registered or requesting.");
            return;
        }

        long playPositionMs = getPlayPosition();

        // mNextPosMs is set to -1 when the previous position was invalid
        // so this will be true if the new position is valid & old was invalid.
        // mPlayPositionMs is set to -1 when the new position is invalid,
        // and the old mPrevPosMs is >= 0 so this is true when the new is invalid
        // and the old was valid.
        if (DEBUG) Log.d(TAG, "sendPlayPosNotificationRsp: (" + requested + ") "
                + mPrevPosMs + " <=? " + playPositionMs + " <=? " + mNextPosMs);
        if (requested || ((mLastReportedPosition != playPositionMs) &&
                (playPositionMs >= mNextPosMs) || (playPositionMs <= mPrevPosMs))) {
            if (!requested) mPlayPosChangedNT = AvrcpConstants.NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspPlayPosNative(mPlayPosChangedNT, (int) playPositionMs);
            mLastReportedPosition = playPositionMs;
            if (playPositionMs != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                mNextPosMs = playPositionMs + mPlaybackIntervalMs;
                mPrevPosMs = playPositionMs - mPlaybackIntervalMs;
            } else {
                mNextPosMs = -1;
                mPrevPosMs = -1;
            }
        }

        mHandler.removeMessages(MSG_PLAY_INTERVAL_TIMEOUT);
        if (mPlayPosChangedNT == AvrcpConstants.NOTIFICATION_TYPE_INTERIM && isPlayingState(mCurrentPlayState)) {
            Message msg = mHandler.obtainMessage(MSG_PLAY_INTERVAL_TIMEOUT);
            long delay = mPlaybackIntervalMs;
            if (mNextPosMs != -1) {
                delay = mNextPosMs - (playPositionMs > 0 ? playPositionMs : 0);
            }
            if (DEBUG) Log.d(TAG, "PLAY_INTERVAL_TIMEOUT set for " + delay + "ms from now");
            mHandler.sendMessageDelayed(msg, delay);
        }
    }

    /**
     * This is called from AudioService. It will return whether this device supports abs volume.
     * NOT USED AT THE MOMENT.
     */
    public boolean isAbsoluteVolumeSupported() {
        return ((mFeatures & BTRC_FEAT_ABSOLUTE_VOLUME) != 0);
    }

    /**
     * We get this call from AudioService. This will send a message to our handler object,
     * requesting our handler to call setVolumeNative()
     */
    public void adjustVolume(int direction) {
        Message msg = mHandler.obtainMessage(MSG_ADJUST_VOLUME, direction, 0);
        mHandler.sendMessage(msg);
    }

    public void setAbsoluteVolume(int volume) {
        if (volume == mLocalVolume) {
            if (DEBUG) Log.v(TAG, "setAbsoluteVolume is setting same index, ignore "+volume);
            return;
        }

        mHandler.removeMessages(MSG_ADJUST_VOLUME);
        Message msg = mHandler.obtainMessage(MSG_SET_ABSOLUTE_VOLUME, volume, 0);
        mHandler.sendMessage(msg);
    }

    /* Called in the native layer as a btrc_callback to return the volume set on the carkit in the
     * case when the volume is change locally on the carkit. This notification is not called when
     * the volume is changed from the phone.
     *
     * This method will send a message to our handler to change the local stored volume and notify
     * AudioService to update the UI
     */
    private void volumeChangeRequestFromNative(byte[] address, int volume, int ctype) {
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_VOLUME_CHANGE, volume, ctype);
        Bundle data = new Bundle();
        data.putByteArray("BdAddress" , address);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    private void getFolderItemsRequestFromNative(byte[] address, byte scope, int startItem, int endItem,
            byte numAttr, int[] attrIds) {
        if (DEBUG) Log.v(TAG, "getFolderItemsRequestFromNative: scope=" + scope + ", numAttr=" + numAttr);
        AvrcpCmd avrcpCmdobj = new AvrcpCmd();
        AvrcpCmd.FolderItemsCmd folderObj = avrcpCmdobj.new FolderItemsCmd(address, scope,
                startItem, endItem, numAttr, attrIds);
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_GET_FOLDER_ITEMS, 0, 0);
        msg.obj = folderObj;
        mHandler.sendMessage(msg);
    }

    private void setAddressedPlayerRequestFromNative(byte[] address, int playerId) {
        if (DEBUG) Log.v(TAG, "setAddrPlayerRequestFromNative: playerId=" + playerId);
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_SET_ADDR_PLAYER, playerId, 0);
        msg.obj = address;
        mHandler.sendMessage(msg);
    }

    private void setBrowsedPlayerRequestFromNative(byte[] address, int playerId) {
        if (DEBUG) Log.v(TAG, "setBrPlayerRequestFromNative: playerId=" + playerId);
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_SET_BR_PLAYER, playerId, 0);
        msg.obj = address;
        mHandler.sendMessage(msg);
    }

    private void changePathRequestFromNative(byte[] address, byte direction, byte[] folderUid) {
        if (DEBUG) Log.v(TAG, "changePathRequestFromNative: direction=" + direction);
        Bundle data = new Bundle();
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_CHANGE_PATH);
        data.putByteArray("BdAddress" , address);
        data.putByteArray("folderUid" , folderUid);
        data.putByte("direction" , direction);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    private void getItemAttrRequestFromNative(byte[] address, byte scope, byte[] itemUid, int uidCounter,
            byte numAttr, int[] attrs) {
        if (DEBUG) Log.v(TAG, "getItemAttrRequestFromNative: scope=" + scope + ", numAttr=" + numAttr);
        AvrcpCmd avrcpCmdobj = new AvrcpCmd();
        AvrcpCmd.ItemAttrCmd itemAttr = avrcpCmdobj.new ItemAttrCmd(address, scope,
                itemUid, uidCounter, numAttr, attrs);
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_GET_ITEM_ATTR);
        msg.obj = itemAttr;
        mHandler.sendMessage(msg);
    }

    private void searchRequestFromNative(byte[] address, int charsetId, byte[] searchStr) {
        if (DEBUG) Log.v(TAG, "searchRequestFromNative");
        /* Search is not supported */
        if (DEBUG) Log.d(TAG, "search is not supported");
        searchRspNative(address, AvrcpConstants.RSP_SRCH_NOT_SPRTD, 0, 0);
    }

    private void playItemRequestFromNative(byte[] address, byte scope, int uidCounter, byte[] uid) {
        if (DEBUG) Log.v(TAG, "playItemRequestFromNative: scope=" + scope);
        Bundle data = new Bundle();
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_PLAY_ITEM);
        data.putByteArray("BdAddress" , address);
        data.putByteArray("uid" , uid);
        data.putInt("uidCounter" , uidCounter);
        data.putByte("scope" , scope);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    private void addToPlayListRequestFromNative(byte[] address, byte scope, byte[] uid, int uidCounter) {
        if (DEBUG) Log.v(TAG, "addToPlayListRequestFromNative: scope=" + scope);
        /* add to NowPlaying not supported */
        Log.w(TAG, "Add to NowPlayingList is not supported");
        addToNowPlayingRspNative(address, AvrcpConstants.RSP_INTERNAL_ERR);
    }

    private void getTotalNumOfItemsRequestFromNative(byte[] address, byte scope) {
        if (DEBUG) Log.v(TAG, "getTotalNumOfItemsRequestFromNative: scope=" + scope);
        Bundle data = new Bundle();
        Message msg = mHandler.obtainMessage(MSG_NATIVE_REQ_GET_TOTAL_NUM_OF_ITEMS);
        msg.arg1 = scope;
        msg.obj = address;
        mHandler.sendMessage(msg);
    }

    private void notifyVolumeChanged(int volume) {
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume,
                      AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
    }

    private int convertToAudioStreamVolume(int volume) {
        // Rescale volume to match AudioSystem's volume
        return (int) Math.floor((double) volume*mAudioStreamMax/AVRCP_MAX_VOL);
    }

    private int convertToAvrcpVolume(int volume) {
        return (int) Math.ceil((double) volume*AVRCP_MAX_VOL/mAudioStreamMax);
    }

    private void blackListCurrentDevice() {
        mFeatures &= ~BTRC_FEAT_ABSOLUTE_VOLUME;
        mAudioManager.avrcpSupportsAbsoluteVolume(mAddress, isAbsoluteVolumeSupported());

        SharedPreferences pref = mContext.getSharedPreferences(ABSOLUTE_VOLUME_BLACKLIST,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putBoolean(mAddress, true);
        editor.apply();
    }

    private int modifyRcFeatureFromBlacklist(int feature, String address) {
        SharedPreferences pref = mContext.getSharedPreferences(ABSOLUTE_VOLUME_BLACKLIST,
                Context.MODE_PRIVATE);
        if (!pref.contains(address)) {
            return feature;
        }
        if (pref.getBoolean(address, false)) {
            feature &= ~BTRC_FEAT_ABSOLUTE_VOLUME;
        }
        return feature;
    }

    public void resetBlackList(String address) {
        SharedPreferences pref = mContext.getSharedPreferences(ABSOLUTE_VOLUME_BLACKLIST,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.remove(address);
        editor.apply();
    }

    /**
     * This is called from A2dpStateMachine to set A2dp audio state.
     */
    public void setA2dpAudioState(int state) {
        Message msg = mHandler.obtainMessage(MSG_SET_A2DP_AUDIO_STATE, state, 0);
        mHandler.sendMessage(msg);
    }

    private class AvrcpServiceBootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                if (DEBUG) Log.d(TAG, "Boot completed, initializing player lists");
                /* initializing media player's list */
                (new BrowsablePlayerListBuilder()).start();
            }
        }
    }

    private class AvrcpServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "AvrcpServiceBroadcastReceiver-> Action: " + action);

            if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_DATA_CLEARED)) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // a package is being removed, not replaced
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName != null) {
                        handlePackageModified(packageName, true);
                    }
                }

            } else if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || action.equals(Intent.ACTION_PACKAGE_CHANGED)) {
                String packageName = intent.getData().getSchemeSpecificPart();
                if (DEBUG) Log.d(TAG,"AvrcpServiceBroadcastReceiver-> packageName: "
                        + packageName);
                if (packageName != null) {
                    handlePackageModified(packageName, false);
                }
            }
        }
    }

    private void handlePackageModified(String packageName, boolean removed) {
        if (DEBUG) Log.d(TAG, "packageName: " + packageName + " removed: " + removed);

        if (removed) {
            // old package is removed, updating local browsable player's list
            if (isBrowseSupported(packageName)) {
                removePackageFromBrowseList(packageName);
            }
        } else {
            // new package has been added.
            if (isBrowsableListUpdated(packageName)) {
                // Rebuilding browsable players list
                (new BrowsablePlayerListBuilder()).start();
            }
        }
    }

    private boolean isBrowsableListUpdated(String newPackageName) {
        // getting the browsable media players list from package manager
        Intent intent = new Intent("android.media.browse.MediaBrowserService");
        List<ResolveInfo> resInfos = mPackageManager.queryIntentServices(intent,
                                         PackageManager.MATCH_ALL);
        for (ResolveInfo resolveInfo : resInfos) {
            if (resolveInfo.serviceInfo.packageName.equals(newPackageName)) {
                if (DEBUG)
                    Log.d(TAG,
                            "isBrowsableListUpdated: package includes MediaBrowserService, true");
                return true;
            }
        }

        // if list has different size
        if (resInfos.size() != mBrowsePlayerInfoList.size()) {
            if (DEBUG) Log.d(TAG, "isBrowsableListUpdated: browsable list size mismatch, true");
            return true;
        }

        Log.d(TAG, "isBrowsableListUpdated: false");
        return false;
    }

    private synchronized void removePackageFromBrowseList(String packageName) {
        if (DEBUG) Log.d(TAG, "removePackageFromBrowseList: " + packageName);
        int browseInfoID = getBrowseId(packageName);
        if (browseInfoID != -1) {
            mBrowsePlayerInfoList.remove(browseInfoID);
        }
    }

    /*
     * utility function to get the browse player index from global browsable
     * list. It may return -1 if specified package name is not in the list.
     */
    private synchronized int getBrowseId(String packageName) {

        boolean response = false;
        int browseInfoID = 0;

        for (BrowsePlayerInfo info : mBrowsePlayerInfoList) {
            if (info.packageName.equals(packageName)) {
                response = true;
                break;
            }
            browseInfoID++;
        }

        if (!response) {
            browseInfoID = -1;
        }

        if (DEBUG) Log.d(TAG, "getBrowseId for packageName: " + packageName +
                " , browseInfoID: " + browseInfoID);
        return browseInfoID;
    }

    private void setAddressedPlayer(byte[] bdaddr, int selectedId) {
        int status = AvrcpConstants.RSP_NO_ERROR;

        if (isCurrentMediaPlayerListEmpty()) {
            status = AvrcpConstants.RSP_NO_AVBL_PLAY;
            Log.w(TAG, " No Available Players to set, sending response back ");
        } else if (!isIdValid(selectedId)) {
            status = AvrcpConstants.RSP_INV_PLAYER;
            Log.w(TAG, " Invalid Player id: " + selectedId + " to set, sending response back ");
        } else if (!isPlayerAlreadyAddressed(selectedId)) {
            // register new Media Controller Callback and update the current Ids
            if (!updateCurrentController(selectedId, mCurrBrowsePlayerID)) {
                status = AvrcpConstants.RSP_INTERNAL_ERR;
                Log.e(TAG, "register for new Address player failed: " + mCurrAddrPlayerID);
            }
        } else {
            Log.i(TAG, "requested addressPlayer is already focused:" + getCurrentAddrPlayer());
        }

        if (DEBUG) Log.d(TAG, "setAddressedPlayer for selectedId: " + selectedId +
                " , status: " + status);
        // Sending address player response to remote
        setAddressedPlayerRspNative(bdaddr, status);
    }

    private void setBrowsedPlayer(byte[] bdaddr, int selectedId) {
        int status = AvrcpConstants.RSP_NO_ERROR;

        // checking for error cases
        if (isCurrentMediaPlayerListEmpty()) {
            status = AvrcpConstants.RSP_NO_AVBL_PLAY;
            Log.w(TAG, " No Available Players to set, sending response back ");
        } else {
            // update current browse player id and start browsing service
            updateNewIds(mCurrAddrPlayerID, selectedId);
            String browsedPackage = getPackageName(selectedId);

            if (!isPackageNameValid(browsedPackage)) {
                Log.w(TAG, " Invalid package for id:" + mCurrBrowsePlayerID);
                status = AvrcpConstants.RSP_INV_PLAYER;
            } else if (!isBrowseSupported(browsedPackage)) {
                Log.w(TAG, "Browse unsupported for id:" + mCurrBrowsePlayerID
                        + ", packagename : " + browsedPackage);
                status = AvrcpConstants.RSP_PLAY_NOT_BROW;
            } else if (!startBrowseService(bdaddr, browsedPackage)) {
                Log.e(TAG, "service cannot be started for browse player id:" + mCurrBrowsePlayerID
                        + ", packagename : " + browsedPackage);
                status = AvrcpConstants.RSP_INTERNAL_ERR;
            }
        }

        if (status != AvrcpConstants.RSP_NO_ERROR) {
            setBrowsedPlayerRspNative(bdaddr, status, (byte) 0x00, 0, null);
        }

        if (DEBUG) Log.d(TAG, "setBrowsedPlayer for selectedId: " + selectedId +
                " , status: " + status);
    }

    private MediaSessionManager.OnActiveSessionsChangedListener mActiveSessionListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {

        @Override
        public void onActiveSessionsChanged(List<android.media.session.MediaController> mediaControllerList) {
            if (DEBUG) Log.v(TAG, "received onActiveSessionsChanged");

            List<MediaController> mediaControllerListTemp = new ArrayList<MediaController>();
            for (android.media.session.MediaController temp : mediaControllerList) {
                mediaControllerListTemp.add(MediaController.wrap(temp));
            }

            if (isAvailablePlayersChanged(mediaControllerListTemp)) {
                // rebuild the list cached locally in this file
                buildMediaPlayersList();

                // inform the remote device that the player list has changed
                sendAvailablePlayersChanged();
            } else if (isAddressedPlayerChanged(mediaControllerListTemp)) {
                int newAddrPlayerID = getNewAddrPlayerID(mediaControllerListTemp.get(0)
                        .getPackageName());
                // inform the remote device that the addressed player has changed
                sendAddressedPlayerChanged(newAddrPlayerID);

                if (!updateCurrentController(newAddrPlayerID, mCurrBrowsePlayerID)) {
                    Log.e(TAG, "register for new Address player failed. id: " + newAddrPlayerID);
                }
            } else {
                if (DEBUG) Log.d(TAG, "Active sessions same, ignoring onActiveSessionsChanged.");
            }
        }

        private void sendAddressedPlayerChanged(int newAddrPlayerID) {
            if (DEBUG) Log.d(TAG, "sendAddressedPlayerChanged: new PlayerID=" + newAddrPlayerID);

            /* notify remote addressed player changed */
            registerNotificationRspAddrPlayerChangedNative(
                    AvrcpConstants.NOTIFICATION_TYPE_CHANGED, newAddrPlayerID, sUIDCounter);
        }

        private void sendAvailablePlayersChanged() {
            if (DEBUG) Log.d(TAG, "sendAvailablePlayersChanged");

            /* Notify remote available players changed */
            registerNotificationRspAvalPlayerChangedNative(
                    AvrcpConstants.NOTIFICATION_TYPE_CHANGED);
        }

        private boolean isAddressedPlayerChanged(List<MediaController> mediaControllerList) {
            boolean isAddrPlayerChanged = false;

            // checking top of the controller's list with current addressed player
            if (mediaControllerList != null && !mediaControllerList.isEmpty()) {
                if (!mediaControllerList.get(0).getPackageName().equals(getCurrentAddrPlayer())) {
                    isAddrPlayerChanged = true;
                }
            }

            if (DEBUG) Log.d(TAG, "isAddressedPlayerChanged: " + isAddrPlayerChanged);
            return isAddrPlayerChanged;
        }

        private boolean isAvailablePlayersChanged(List<MediaController> mediaControllerList) {
            boolean isListModified = false;

            /* comparing media controller list from framework and from local cached list */
            if (mediaControllerList == null && isCurrentMediaPlayerListEmpty()) {
                if (DEBUG) Log.d(TAG,
                        "both player list, received from framework and local are empty");
                return isListModified;
            }

            if (mediaControllerList == null && !isCurrentMediaPlayerListEmpty()) {
                isListModified = true;
                if (DEBUG) Log.d(TAG, "players list is empty, local player list is not empty");
            } else if (isCurrentMediaPlayerListEmpty() && mediaControllerList != null) {
                if (DEBUG) Log.d(TAG, "players list is not empty, but local player list is empty");
                isListModified = true;
            } else if (isCtrlListChanged(mediaControllerList, mMPLObj.mControllersList)) {
                isListModified = true;
            }

            if (DEBUG) Log.d(TAG, "isAvailablePlayersChanged: " + isListModified);
            return isListModified;
        }

        private int getNewAddrPlayerID(String newAddressedPlayer) {
            int newAddrPlayerId = -1;

            for (int id = 0; id < mMPLObj.mPackageNameList.length; id++) {
                if (mMPLObj.mPackageNameList[id].equals(newAddressedPlayer)) {
                    // increment Id by one, because list Ids starts from 1.
                    newAddrPlayerId = id + 1;
                    break;
                }
            }

            if (DEBUG) Log.d(TAG, "getNewAddrPlayerID: " + newAddrPlayerId);
            return newAddrPlayerId;
        }

        private boolean isCtrlListChanged(List<MediaController> mediaControllerList,
                List<MediaController> mControllersList) {
            boolean isListChanged = false;

            if (mControllersList.size() != mediaControllerList.size()) {
                if (DEBUG) Log.d(TAG, "size of new list and old list are different");
                isListChanged = true;
            } else {
                // loop through both the list and check if any new entry found
                for (MediaController newCtrller : mediaControllerList) {
                    boolean isPackageExist = false;

                    for (MediaController oldCtrller : mControllersList) {
                        if (oldCtrller.getPackageName().equals(newCtrller.getPackageName())) {
                            isPackageExist = true;
                            break;
                        }
                    }

                    if (!isPackageExist) {
                        if (DEBUG) Log.d(TAG, "no match found for " + newCtrller.getPackageName());
                        isListChanged =  true;
                        break;
                    }
                }
            }

            if (DEBUG) Log.d(TAG, "isCtrlListChanged: " + isListChanged);
            return isListChanged;
        }

    };

    private boolean startBrowseService(byte[] bdaddr, String packageName) {
        boolean status = true;

        /* creating new instance for Browse Media Player */
        String browseService = getBrowseServiceName(packageName);
        if (!browseService.isEmpty()) {
            mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr).setBrowsed(
                    packageName, browseService);
        } else {
            Log.w(TAG, "No Browser service available for " + packageName);
            status = false;
        }

        if (DEBUG) Log.d(TAG, "startBrowseService for packageName: " + packageName +
                ", status = " + status);
        return status;
    }

    private synchronized String getBrowseServiceName(String packageName) {
        String browseServiceName = "";

        // getting the browse service name from browse player info
        int browseInfoID = getBrowseId(packageName);
        if (browseInfoID != -1) {
            browseServiceName = mBrowsePlayerInfoList.get(browseInfoID).serviceClass;
        }

        if (DEBUG) Log.d(TAG, "getBrowseServiceName for packageName: " + packageName +
                ", browseServiceName = " + browseServiceName);
        return browseServiceName;
    }

    private class BrowsablePlayerListBuilder extends MediaBrowser.ConnectionCallback {
        List<ResolveInfo> mWaiting;
        BrowsePlayerInfo mCurrentPlayer;
        MediaBrowser mCurrentBrowser;

        public BrowsablePlayerListBuilder() {}

        public void start() {
            mBrowsePlayerInfoList.clear();
            Intent intent = new Intent(android.service.media.MediaBrowserService.SERVICE_INTERFACE);
            mWaiting = mPackageManager.queryIntentServices(intent, PackageManager.MATCH_ALL);
            connectNextPlayer();
        }

        private void connectNextPlayer() {
            if (mWaiting.isEmpty()) {
                // Done. Build the MediaPlayersList.
                buildMediaPlayersList();
                return;
            }
            ResolveInfo info = mWaiting.remove(0);
            String displayableName = info.loadLabel(mPackageManager).toString();
            String serviceName = info.serviceInfo.name;
            String packageName = info.serviceInfo.packageName;

            mCurrentPlayer = new BrowsePlayerInfo(packageName, displayableName, serviceName);
            mCurrentBrowser = new MediaBrowser(
                    mContext, new ComponentName(packageName, serviceName), this, null);
            if (DEBUG) Log.d(TAG, "Trying to connect to " + serviceName);
            mCurrentBrowser.connect();
        }

        @Override
        public void onConnected() {
            Log.d(TAG, "BrowsablePlayerListBuilder: " + mCurrentPlayer.packageName + " OK");
            mBrowsePlayerInfoList.add(mCurrentPlayer);
            mCurrentBrowser.disconnect();
            connectNextPlayer();
        }

        @Override
        public void onConnectionFailed() {
            Log.d(TAG, "BrowsablePlayerListBuilder: " + mCurrentPlayer.packageName + " FAIL");
            connectNextPlayer();
        }

    }

    /* initializing media player info list and prepare media player response object */
    private void buildMediaPlayersList() {
        initMediaPlayersInfoList();
        mMPLObj = prepareMediaPlayerRspObj();

        if (mMPLObj.mNumItems > 0) {
            /* Set the first one as the Addressed Player */
            updateCurrentController(1, -1);
        } else {
            Log.i(TAG, "No players available in the media players list");
            /* No players have browsing started. Start one so we can handle commands. */
            if ((mBrowsePlayerInfoList != null) && (mBrowsePlayerInfoList.size()!=0)) {
                BrowsePlayerInfo player = mBrowsePlayerInfoList.get(0);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(player.packageName, player.serviceClass));
                Log.i(TAG, "Starting service:" + player.packageName + ", " + player.serviceClass);
                try {
                    mContext.startService(intent);
                } catch (SecurityException ex) {
                    Log.e(TAG, "Can't start " + player.serviceClass + ": " + ex.getMessage());
                }
            } else {
                Log.e(TAG, "Opening player to support AVRCP operations failed, " +
                        "No browsable players available!");
            }
        }

    }

    /*
     * utility function to build list of active media players identified from
     * session manager by getting the active sessions
     */
    private synchronized void initMediaPlayersInfoList() {

        // Clearing old browsable player's list
        mMediaPlayerInfoList.clear();

        if (mMediaSessionManager == null) {
            if (DEBUG) Log.w(TAG, "initMediaPlayersInfoList: no media session manager!");
            return;
        }

        List<android.media.session.MediaController> controllers =
                mMediaSessionManager.getActiveSessions(null);
        if (DEBUG) Log.v(TAG, "initMediaPlayerInfoList: " + controllers.size() + " controllers");
        /* Initializing all media players */
        for (android.media.session.MediaController mediaController : controllers) {
            String packageName = mediaController.getPackageName();
            MediaController controller = MediaController.wrap(mediaController);
            MediaPlayerInfo info =
                    new MediaPlayerInfo(packageName, AvrcpConstants.PLAYER_TYPE_AUDIO,
                            AvrcpConstants.PLAYER_SUBTYPE_NONE, getPlayBackState(controller),
                            getFeatureBitMask(packageName), getAppLabel(packageName), controller);
            if (DEBUG) Log.d(TAG, info.toString());
            mMediaPlayerInfoList.add(info);
        }
    }

    /*
     * utility function to get the playback state of any media player through
     * media controller APIs.
     */
    private byte getPlayBackState(MediaController mediaController) {
        PlaybackState pbState = mediaController.getPlaybackState();
        byte playStateBytes = PLAYSTATUS_STOPPED;

        if (pbState != null) {
            playStateBytes = (byte)convertPlayStateToBytes(pbState.getState());
            Log.v(TAG, "getPlayBackState: playStateBytes = " + playStateBytes);
        } else {
            Log.w(TAG, "playState object null, sending playStateBytes = " + playStateBytes);
        }

        return playStateBytes;
    }

    /*
     * utility function to map framework's play state values to AVRCP spec
     * defined play status values
     */
    private int convertPlayStateToBytes(int playState) {
        switch (playState) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
                return PLAYSTATUS_PLAYING;

            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE:
            case PlaybackState.STATE_CONNECTING:
                return PLAYSTATUS_STOPPED;

            case PlaybackState.STATE_PAUSED:
                return PLAYSTATUS_PAUSED;

            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return PLAYSTATUS_FWD_SEEK;

            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                return PLAYSTATUS_REV_SEEK;

            case PlaybackState.STATE_ERROR:
            default:
                return PLAYSTATUS_ERROR;
        }
    }

    /*
     * utility function to get the feature bit mask of any media player through
     * package name
     */
    private short[] getFeatureBitMask(String packageName) {

        ArrayList<Short> featureBitsList = new ArrayList<Short>();

        /* adding default feature bits */
        featureBitsList.add(AvrcpConstants.AVRC_PF_PLAY_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_STOP_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_PAUSE_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_REWIND_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_FAST_FWD_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_FORWARD_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_BACKWARD_BIT_NO);
        featureBitsList.add(AvrcpConstants.AVRC_PF_ADV_CTRL_BIT_NO);

        /* Add/Modify browse player supported features. */
        if (isBrowseSupported(packageName)) {
            featureBitsList.add(AvrcpConstants.AVRC_PF_BROWSE_BIT_NO);
            featureBitsList.add(AvrcpConstants.AVRC_PF_UID_UNIQUE_BIT_NO);
            featureBitsList.add(AvrcpConstants.AVRC_PF_NOW_PLAY_BIT_NO);
            featureBitsList.add(AvrcpConstants.AVRC_PF_GET_NUM_OF_ITEMS_BIT_NO);
        }

        // converting arraylist to array for response
        short[] featureBitsArray = new short[featureBitsList.size()];

        for (int i = 0; i < featureBitsList.size(); i++) {
            featureBitsArray[i] = featureBitsList.get(i).shortValue();
        }

        return featureBitsArray;
    }

    /**
     * Checks the Package name if it supports Browsing or not.
     *
     * @param packageName - name of the package to get the Id.
     * @return true if it supports browsing, else false.
     */
    private synchronized boolean isBrowseSupported(String packageName) {
        /* check if Browsable Player's list contains this package name */
        for (BrowsePlayerInfo info : mBrowsePlayerInfoList) {
            if (info.packageName.equals(packageName)) {
                if (DEBUG) Log.v(TAG, "isBrowseSupported for " + packageName +
                                      ": true");
                return true;
            }
        }

        if (DEBUG) Log.v(TAG, "isBrowseSupported for " + packageName +
                              ": false");
        return false;
    }

    /* from the global object, getting the current addressed player's package name */
    private String getCurrentAddrPlayer() {
        String addrPlayerPackage = "";

        if (!isCurrentMediaPlayerListEmpty() && isIdValid(mCurrAddrPlayerID)) {
            addrPlayerPackage = mMPLObj.mPackageNameList[mCurrAddrPlayerID - 1];
            if (DEBUG) Log.v(TAG, "Current Addressed Player's Package: " + addrPlayerPackage);
        } else {
            Log.w(TAG, "current addressed player is not yet set.");
        }
        return addrPlayerPackage;
    }

    private String getPackageName(int id) {
        String packageName = "";

        if (!isCurrentMediaPlayerListEmpty() && isIdValid(id)) {
            packageName = mMPLObj.mPackageNameList[id - 1];
            if (DEBUG) Log.v(TAG, "Current Player's Package: " + packageName);
        } else {
            Log.w(TAG, "Current media player is empty or id is invalid");
        }
        return packageName;
    }

    /* from the global object, getting the current browsed player's package name */
    private String getCurrentBrowsedPlayer(byte[] bdaddr) {
        String browsedPlayerPackage = "";

        Map<String, BrowsedMediaPlayer> connList = mAvrcpBrowseManager.getConnList();
        String bdaddrStr = new String(bdaddr);
        if(connList.containsKey(bdaddrStr)){
            browsedPlayerPackage = connList.get(bdaddrStr).getPackageName();
        }
        if (DEBUG) Log.v(TAG, "getCurrentBrowsedPlayerPackage: " + browsedPlayerPackage);
        return browsedPlayerPackage;
    }

    /*
     * utility function to get the media controller from the current addressed
     * player id, can return null in error cases
     */
    private synchronized MediaController getCurrentMediaController() {
        MediaController mediaController = null;

        if (mMediaPlayerInfoList == null || mMediaPlayerInfoList.isEmpty()) {
            Log.w(TAG, " No available players , sending response back ");
            return mediaController;
        }

        if (!isIdValid(mCurrAddrPlayerID)) {
            Log.w(TAG, "CurrPlayerID is not yet set:" + mCurrAddrPlayerID + ", PlayerList length="
                    + mMediaPlayerInfoList.size() + " , sending response back");
            return mediaController;
        }

        mediaController = mMediaPlayerInfoList.get(mCurrAddrPlayerID - 1).getMediaController();

        if (mediaController != null) {
            if (DEBUG)
                Log.v(TAG, "getCurrentMediaController: " + mediaController.getPackageName());
        }

        return mediaController;
    }

    /*
     * Utility function to get the Media player info from package name returns
     * null if package name not found in media players list
     */
    private synchronized MediaPlayerInfo getMediaPlayerInfo(String packageName) {
        if (DEBUG) Log.v(TAG, "getMediaPlayerInfo: " + packageName);
        if (mMediaPlayerInfoList.size() > 0) {
            for (MediaPlayerInfo info : mMediaPlayerInfoList) {
                if (packageName.equals(info.getPackageName())) {
                    if (DEBUG) Log.v(TAG, "Found " + info.getPackageName());
                    return info;
                }
            }
        } else {
            if (DEBUG) Log.v(TAG, "Media players list empty");
        }
        return null;
    }

    /* prepare media list & return the media player list response object */
    private synchronized MediaPlayerListRsp prepareMediaPlayerRspObj() {

        /* Forming player list -- */
        int numPlayers = mMediaPlayerInfoList.size();

        byte[] playerTypes = new byte[numPlayers];
        int[] playerSubTypes = new int[numPlayers];
        String[] displayableNameArray = new String[numPlayers];
        String[] packageNameArray = new String[numPlayers];
        byte[] playStatusValues = new byte[numPlayers];
        short[] featureBitMaskValues = new short[numPlayers
                * AvrcpConstants.AVRC_FEATURE_MASK_SIZE];
        List<MediaController> mediaControllerList = new ArrayList<MediaController>();

        int playerId = 0;
        for (MediaPlayerInfo info : mMediaPlayerInfoList) {
            playerTypes[playerId] = info.getMajorType();
            playerSubTypes[playerId] = info.getSubType();
            packageNameArray[playerId] = info.getPackageName();
            displayableNameArray[playerId] = info.getDisplayableName();
            playStatusValues[playerId] = info.getPlayStatus();
            mediaControllerList.add(info.getMediaController());

            for (int numBit = 0; numBit < info.getFeatureBitMask().length; numBit++) {
                /* gives which octet this belongs to */
                byte octet = (byte) (info.getFeatureBitMask()[numBit] / 8);
                /* gives the bit position within the octet */
                byte bit = (byte) (info.getFeatureBitMask()[numBit] % 8);
                featureBitMaskValues[(playerId * AvrcpConstants.AVRC_FEATURE_MASK_SIZE) + octet] |=
                        (1 << bit);
            }

            /* printLogs */
            if (DEBUG) {
                Log.d(TAG, "\n   +++ Player " + playerId + " +++   ");
                Log.d(TAG, "display Name[" + playerId + "]: " + displayableNameArray[playerId]);
                Log.d(TAG, "Package Name[" + playerId + "]: " + packageNameArray[playerId]);
                Log.d(TAG, "player Types[" + playerId + "]: " + playerTypes[playerId]);
                Log.d(TAG, "Play Status Value[" + playerId + "]: " + playStatusValues[playerId]);
                Log.d(TAG, "\n");
            }

            playerId++;
        }

        if (DEBUG) Log.d(TAG, "prepareMediaPlayerRspObj: numPlayers = " + numPlayers);

        return new MediaPlayerListRsp(AvrcpConstants.RSP_NO_ERROR, sUIDCounter,
                numPlayers, AvrcpConstants.BTRC_ITEM_PLAYER, playerTypes, playerSubTypes,
                playStatusValues, featureBitMaskValues,
                displayableNameArray, packageNameArray, mediaControllerList);

    }

     /* build media player list and send it to remote. */
    private void handleMediaPlayerListRsp(AvrcpCmd.FolderItemsCmd folderObj) {
        if (folderObj.mStartItem >= mMPLObj.mNumItems) {
            Log.i(TAG, "handleMediaPlayerListRsp: start item = " + folderObj.mStartItem +
                    ", but available num of items = " + mMPLObj.mNumItems);
            mediaPlayerListRspNative(folderObj.mAddress, AvrcpConstants.RSP_INV_RANGE,
                    (short) 0, (byte) 0, 0, null, null, null, null, null);
        } else {
            if (DEBUG) Log.d(TAG, "handleMediaPlayerListRsp: num items = " + mMPLObj.mNumItems);
            sendFolderItems(mMPLObj, folderObj.mAddress);
        }
    }

    /* unregister to the old controller, update new IDs and register to the new controller */
    private boolean updateCurrentController(int addrId, int browseId) {
        boolean registerRsp = true;

        if (!unregOldMediaControllerCb()) {
            Log.d(TAG, "unregisterOldMediaControllerCallback return false");
        }

        updateNewIds(addrId, browseId);
        if (!regNewMediaControllerCb()) {
            Log.d(TAG, "registerOldMediaControllerCallback return false");
            registerRsp = false;
        }

        if (DEBUG) Log.d(TAG, "updateCurrentController: registerRsp = " + registerRsp);
        return registerRsp;
    }

    /* get the current media controller and unregister for the media controller callback */
    private boolean unregOldMediaControllerCb() {
        boolean isUnregistered = false;

        // unregistering callback for old media controller.
        MediaController oldController = getCurrentMediaController();
        if (oldController != null) {
            oldController.unregisterCallback(mMediaControllerCb);
            isUnregistered = true;
        } else {
            Log.i(TAG, "old controller is null, addressPlayerId:" + mCurrAddrPlayerID);
        }

        if (DEBUG) Log.d(TAG, "unregOldMediaControllerCb: isUnregistered = " + isUnregistered);
        return isUnregistered;
    }

    /* get the current media controller and register for the media controller callback */
    private boolean regNewMediaControllerCb() {
        // registering callback for new media controller.
        MediaController newController = getCurrentMediaController();
        mMediaController = newController;

        String name = (mMediaController == null) ? "null" : mMediaController.getPackageName();
        Log.v(TAG, "MediaController changed to " + name);

        if (mMediaController == null) {
            Log.i(TAG, "new controller is null, addressPlayerId:" + mCurrAddrPlayerID);
            updateMetadata(null);
            mAddressedMediaPlayer.updateNowPlayingList(null);
            return false;
        }

        mMediaController.registerCallback(mMediaControllerCb, mHandler);
        updateMetadata(mMediaController.getMetadata());
        mAddressedMediaPlayer.updateNowPlayingList(mMediaController.getQueue());
        return true;
    }

    /* Handle getfolderitems for scope = VFS, Search, NowPlayingList */
    private void handleGetFolderItemBrowseResponse(AvrcpCmd.FolderItemsCmd folderObj, byte[] bdaddr) {
        int status = AvrcpConstants.RSP_NO_ERROR;

        /* Browsed player is already set */
        switch (folderObj.mScope) {
            case AvrcpConstants.BTRC_SCOPE_FILE_SYSTEM:
                if (mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr) != null) {
                    mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr).getFolderItemsVFS(folderObj);
                } else {
                    /* No browsed player set. Browsed player should be set by CT before performing browse.*/
                    Log.e(TAG, "handleGetFolderItemBrowseResponse: mBrowsedMediaPlayer is null");
                    status = AvrcpConstants.RSP_INTERNAL_ERR;
                }
                break;

            case AvrcpConstants.BTRC_SCOPE_NOW_PLAYING:
                mAddressedMediaPlayer.getFolderItemsNowPlaying(bdaddr, folderObj, mMediaController);
                break;

            default:
                /* invalid scope */
                Log.e(TAG, "handleGetFolderItemBrowseResponse:invalid scope");
                status = AvrcpConstants.RSP_INV_SCOPE;
        }


        if (status != AvrcpConstants.RSP_NO_ERROR) {
            getFolderItemsRspNative(bdaddr, status, (short) 0, (byte) 0x00, 0, null, null, null,
                null, null, null, null, null);
        }

    }

    /* utility function to update the global values of current Addressed and browsed player */
    private synchronized void updateNewIds(int addrId, int browseId) {
        mCurrAddrPlayerID = addrId;
        mCurrBrowsePlayerID = browseId;

        if (DEBUG) Log.v(TAG, "Updated CurrentIds: AddrPlayerID:" + mCurrAddrPlayerID + " to "
                + addrId + ", BrowsePlayerID:" + mCurrBrowsePlayerID + " to " + browseId);
    }

    /* Getting the application's displayable name from package name */
    private String getAppLabel(String packageName) {
        ApplicationInfo appInfo = null;
        try {
            appInfo = mPackageManager.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }

        return (String) (appInfo != null ? mPackageManager
                .getApplicationLabel(appInfo) : "Unknown");
    }

    private void sendFolderItems(MediaPlayerListRsp rspObj, byte[] bdaddr) {
        mediaPlayerListRspNative(bdaddr, rspObj.mStatus, rspObj.mUIDCounter, rspObj.itemType,
                rspObj.mNumItems, rspObj.mPlayerTypes, rspObj.mPlayerSubTypes,
                rspObj.mPlayStatusValues, rspObj.mFeatureBitMaskValues, rspObj.mPlayerNameList);
    }

    private void handlePlayItemResponse(byte[] bdaddr, byte[] uid, byte scope) {

        if(scope == AvrcpConstants.BTRC_SCOPE_NOW_PLAYING) {
            mAddressedMediaPlayer.playItem(bdaddr, uid, scope, mMediaController);
        }
        else {
            if(!isAddrPlayerSameAsBrowsed(bdaddr)) {
                Log.w(TAG, "Remote requesting play item on uid which may not be recognized by" +
                        "current addressed player");
                playItemRspNative(bdaddr, AvrcpConstants.RSP_INV_ITEM);
            }

            if (mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr) != null) {
                mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr).playItem(uid, scope);
            } else {
                Log.e(TAG, "handlePlayItemResponse: Remote requested playitem " +
                        "before setbrowsedplayer");
                playItemRspNative(bdaddr, AvrcpConstants.RSP_INTERNAL_ERR);
            }
        }
    }

    private void handleGetItemAttr(AvrcpCmd.ItemAttrCmd itemAttr) {
        if(itemAttr.mScope == AvrcpConstants.BTRC_SCOPE_NOW_PLAYING) {
            mAddressedMediaPlayer.getItemAttr(itemAttr.mAddress, itemAttr, mMediaController);
        }
        else {
            if (mAvrcpBrowseManager.getBrowsedMediaPlayer(itemAttr.mAddress) != null)
                mAvrcpBrowseManager.getBrowsedMediaPlayer(itemAttr.mAddress).getItemAttr(itemAttr);
            else {
                Log.e(TAG, "Could not get attributes. mBrowsedMediaPlayer is null");
                getItemAttrRspNative(itemAttr.mAddress, AvrcpConstants.RSP_INTERNAL_ERR,
                        (byte) 0, null, null);
            }
        }
    }

    private void handleGetTotalNumOfItemsResponse(byte[] bdaddr, byte scope) {
        // for scope as media player list
        if (scope == AvrcpConstants.BTRC_SCOPE_PLAYER_LIST) {
            int numPlayers = getPlayerListSize();
            if (DEBUG) Log.d(TAG, "handleGetTotalNumOfItemsResponse: sending total " + numPlayers +
                    " media players.");
            getTotalNumOfItemsRspNative(bdaddr, AvrcpConstants.RSP_NO_ERROR, 0,
                    numPlayers);
        } else if(scope == AvrcpConstants.BTRC_SCOPE_NOW_PLAYING) {
            mAddressedMediaPlayer.getTotalNumOfItems(bdaddr, scope, mMediaController);
        } else {
            // for FileSystem browsing scopes as VFS, Now Playing
            if (mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr) != null) {
                mAvrcpBrowseManager.getBrowsedMediaPlayer(bdaddr).getTotalNumOfItems(scope);
            } else {
                Log.e(TAG, "Could not get Total NumOfItems. mBrowsedMediaPlayer is null");
                getTotalNumOfItemsRspNative(bdaddr, AvrcpConstants.RSP_INTERNAL_ERR, 0, 0);
            }
        }

    }

    private synchronized int getPlayerListSize() {
        return mMediaPlayerInfoList.size();
    }

    /* check if browsed player and addressed player are same */
    private boolean isAddrPlayerSameAsBrowsed(byte[] bdaddr) {
        boolean isSame = true;
        String browsedPlayer = getCurrentBrowsedPlayer(bdaddr);
        String addressedPlayer = getCurrentAddrPlayer();

        if (!isPackageNameValid(browsedPlayer)) {
            Log.w(TAG, "Browsed player name empty");
            isSame = false;
        } else if (!addressedPlayer.equals(browsedPlayer)) {
            Log.w(TAG, browsedPlayer + " is not current Addressed Player : "
                    + addressedPlayer);
            isSame = false;
        }

        if (DEBUG) Log.d(TAG, "isAddrPlayerSameAsBrowsed: isSame = " + isSame);
        return isSame;
    }

    /* checks if global object containing media player list is empty */
    private boolean isCurrentMediaPlayerListEmpty() {
        boolean isEmpty = (mMPLObj == null || mMPLObj.mPackageNameList == null
                || mMPLObj.mPackageNameList.length == 0 || mMediaPlayerInfoList.isEmpty());
        if (DEBUG) Log.d(TAG, "Current MediaPlayer List Empty.= " + isEmpty);
        return isEmpty;
    }

    /* checks if the id is within the range of global object containing media player list */
    private boolean isIdValid(int id) {
        boolean isValid = (id > 0 && id <= mMPLObj.mPackageNameList.length);
        if (DEBUG) Log.d(TAG, "Id = " + id + "isIdValid = " + isValid);
        return isValid;
    }

    /* checks if package name is not null or empty */
    private boolean isPackageNameValid(String browsedPackage) {
        boolean isValid = (browsedPackage != null && browsedPackage.length() > 0);
        if (DEBUG) Log.d(TAG, "isPackageNameValid: browsedPackage = " + browsedPackage +
                "isValid = " + isValid);
        return isValid;
    }

    /* checks if selected addressed player is already addressed */
    private boolean isPlayerAlreadyAddressed(int selectedId) {
        // checking if selected ID is same as the current addressed player id
        boolean isAddressed = (mCurrAddrPlayerID == selectedId);
        if (DEBUG) Log.d(TAG, "isPlayerAlreadyAddressed: isAddressed = " + isAddressed);
        return isAddressed;
    }

    public void dump(StringBuilder sb) {
        sb.append("AVRCP:\n");
        ProfileService.println(sb, "mMediaAttributes: " + mMediaAttributes);
        ProfileService.println(sb, "mTransportControlFlags: " + mTransportControlFlags);
        ProfileService.println(sb, "mTracksPlayed: " + mTracksPlayed);
        ProfileService.println(sb, "mCurrentPlayState: " + mCurrentPlayState);
        ProfileService.println(sb, "mLastStateUpdate: " + mLastStateUpdate);
        ProfileService.println(sb, "mPlayStatusChangedNT: " + mPlayStatusChangedNT);
        ProfileService.println(sb, "mTrackChangedNT: " + mTrackChangedNT);
        ProfileService.println(sb, "mSongLengthMs: " + mSongLengthMs);
        ProfileService.println(sb, "mPlaybackIntervalMs: " + mPlaybackIntervalMs);
        ProfileService.println(sb, "mPlayPosChangedNT: " + mPlayPosChangedNT);
        ProfileService.println(sb, "mNextPosMs: " + mNextPosMs);
        ProfileService.println(sb, "mPrevPosMs: " + mPrevPosMs);
        ProfileService.println(sb, "mSkipStartTime: " + mSkipStartTime);
        ProfileService.println(sb, "mFeatures: " + mFeatures);
        ProfileService.println(sb, "mRemoteVolume: " + mRemoteVolume);
        ProfileService.println(sb, "mLastRemoteVolume: " + mLastRemoteVolume);
        ProfileService.println(sb, "mLastDirection: " + mLastDirection);
        ProfileService.println(sb, "mVolumeStep: " + mVolumeStep);
        ProfileService.println(sb, "mAudioStreamMax: " + mAudioStreamMax);
        ProfileService.println(sb, "mVolCmdAdjustInProgress: " + mVolCmdAdjustInProgress);
        ProfileService.println(sb, "mVolCmdSetInProgress: " + mVolCmdSetInProgress);
        ProfileService.println(sb, "mAbsVolRetryTimes: " + mAbsVolRetryTimes);
        ProfileService.println(sb, "mSkipAmount: " + mSkipAmount);
        ProfileService.println(sb, "mVolumeMapping: " + mVolumeMapping.toString());
        if (mMediaController != null)
            ProfileService.println(sb, "mMediaSession pkg: " + mMediaController.getPackageName());
    }

    public class AvrcpBrowseManager {
        Map<String, BrowsedMediaPlayer> connList = new HashMap<String, BrowsedMediaPlayer>();
        private AvrcpMediaRspInterface mMediaInterface;
        private Context mContext;

        public AvrcpBrowseManager(Context context, AvrcpMediaRspInterface mediaInterface) {
            mContext = context;
            mMediaInterface = mediaInterface;
        }

        public void cleanup() {
            Iterator entries = connList.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry entry = (Map.Entry) entries.next();
                BrowsedMediaPlayer browsedMediaPlayer = (BrowsedMediaPlayer) entry.getValue();
                if (browsedMediaPlayer != null) {
                    browsedMediaPlayer.cleanup();
                }
            }
            // clean up the map
            connList.clear();
        }

        // get the a free media player interface based on the passed bd address
        // if the no items is found for the passed media player then it assignes a
        // available media player interface
        public BrowsedMediaPlayer getBrowsedMediaPlayer(byte[] bdaddr) {
            BrowsedMediaPlayer mediaPlayer;
            String bdaddrStr = new String(bdaddr);
            if(connList.containsKey(bdaddrStr)){
                mediaPlayer = connList.get(bdaddrStr);
            } else {
                mediaPlayer = new BrowsedMediaPlayer(bdaddr, mContext, mMediaInterface);
                connList.put(bdaddrStr, mediaPlayer);
            }
            return mediaPlayer;
        }

        // clears the details pertaining to passed bdaddres
        public boolean clearBrowsedMediaPlayer(byte[] bdaddr) {
            String bdaddrStr = new String(bdaddr);
            if(connList.containsKey(bdaddrStr)) {
                connList.remove(bdaddrStr);
                return true;
            }
            return false;
        }

        public Map<String, BrowsedMediaPlayer> getConnList() {
            return connList;
        }

        /* Helper function to convert colon separated bdaddr to byte string */
        private byte[] hexStringToByteArray(String s) {
            int len = s.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                        + Character.digit(s.charAt(i+1), 16));
            }
            return data;
        }
    }

    /*
     * private class which handles responses from AvrcpMediaManager. Maps responses to native
     * responses. This class implements the AvrcpMediaRspInterface interface.
     */
    private class AvrcpMediaRsp implements AvrcpMediaRspInterface {
        private static final String TAG = "AvrcpMediaRsp";

        public void setAddrPlayerRsp(byte[] address, int rspStatus) {
            if (!setAddressedPlayerRspNative(address, rspStatus)) {
                Log.e(TAG, "setAddrPlayerRsp failed!");
            }
        }

        public void setBrowsedPlayerRsp(byte[] address, int rspStatus, byte depth, int numItems,
                String[] textArray) {
            if (!setBrowsedPlayerRspNative(address, rspStatus, depth, numItems, textArray)) {
                Log.e(TAG, "setBrowsedPlayerRsp failed!");
            }
        }

        public void mediaPlayerListRsp(byte[] address, int rspStatus, MediaPlayerListRsp rspObj) {
            if (rspObj != null && rspStatus == AvrcpConstants.RSP_NO_ERROR) {
                if (!mediaPlayerListRspNative(address, rspStatus, sUIDCounter, rspObj.itemType,
                        rspObj.mNumItems, rspObj.mPlayerTypes, rspObj.mPlayerSubTypes,
                        rspObj.mPlayStatusValues, rspObj.mFeatureBitMaskValues,
                        rspObj.mPlayerNameList))
                        Log.e(TAG, "mediaPlayerListRsp failed!");
            } else {
                Log.e(TAG, "mediaPlayerListRsp: rspObj is null");
                if (!mediaPlayerListRspNative(address, rspStatus, sUIDCounter,
                        (byte)0x00, 0, null, null, null, null, null))
                        Log.e(TAG, "mediaPlayerListRsp failed!");
            }
        }

        public void folderItemsRsp(byte[] address, int rspStatus, FolderItemsRsp rspObj) {
            if (rspObj != null && rspStatus == AvrcpConstants.RSP_NO_ERROR) {
                if (!getFolderItemsRspNative(address, rspStatus, sUIDCounter, rspObj.mScope,
                        rspObj.mNumItems, rspObj.mFolderTypes, rspObj.mPlayable, rspObj.mItemTypes,
                        rspObj.mItemUid, rspObj.mDisplayNames, rspObj.mAttributesNum,
                        rspObj.mAttrIds, rspObj.mAttrValues))
                    Log.e(TAG, "getFolderItemsRspNative failed!");
            } else {
                Log.e(TAG, "folderItemsRsp: rspObj is null or rspStatus is error:" + rspStatus);
                if (!getFolderItemsRspNative(address, rspStatus, sUIDCounter, (byte) 0x00, 0,
                        null, null, null, null, null, null, null, null))
                    Log.e(TAG, "getFolderItemsRspNative failed!");
            }

        }

        public void changePathRsp(byte[] address, int rspStatus, int numItems) {
            if (!changePathRspNative(address, rspStatus, numItems))
                Log.e(TAG, "changePathRspNative failed!");
        }

        public void getItemAttrRsp(byte[] address, int rspStatus, ItemAttrRsp rspObj) {
            if (rspObj != null && rspStatus == AvrcpConstants.RSP_NO_ERROR) {
                if (!getItemAttrRspNative(address, rspStatus, rspObj.mNumAttr,
                        rspObj.mAttributesIds, rspObj.mAttributesArray))
                    Log.e(TAG, "getItemAttrRspNative failed!");
            } else {
                Log.e(TAG, "getItemAttrRsp: rspObj is null or rspStatus is error:" + rspStatus);
                if (!getItemAttrRspNative(address, rspStatus, (byte) 0x00, null, null))
                    Log.e(TAG, "getItemAttrRspNative failed!");
            }
        }

        public void playItemRsp(byte[] address, int rspStatus) {
            if (!playItemRspNative(address, rspStatus)) {
                Log.e(TAG, "playItemRspNative failed!");
            }
        }

        public void getTotalNumOfItemsRsp(byte[] address, int rspStatus, int uidCounter,
                int numItems) {
            if (!getTotalNumOfItemsRspNative(address, rspStatus, sUIDCounter, numItems)) {
                Log.e(TAG, "getTotalNumOfItemsRspNative failed!");
            }
        }

        public void addrPlayerChangedRsp(byte[] address, int type, int playerId, int uidCounter) {
            if (!registerNotificationRspAddrPlayerChangedNative(type, playerId, sUIDCounter)) {
                Log.e(TAG, "registerNotificationRspAddrPlayerChangedNative failed!");
            }
        }

        public void avalPlayerChangedRsp(byte[] address, int type) {
            if (!registerNotificationRspAvalPlayerChangedNative(type)) {
                Log.e(TAG, "registerNotificationRspAvalPlayerChangedNative failed!");
            }
        }

        public void uidsChangedRsp(byte[] address, int type, int uidCounter) {
            if (!registerNotificationRspUIDsChangedNative(type, sUIDCounter)) {
                Log.e(TAG, "registerNotificationRspUIDsChangedNative failed!");
            }
        }

        public void nowPlayingChangedRsp(int type) {
            if (!registerNotificationRspNowPlayingChangedNative(type)) {
                Log.e(TAG, "registerNotificationRspNowPlayingChangedNative failed!");
            }
        }

        public void trackChangedRsp(int type, byte[] uid) {
            if (!registerNotificationRspTrackChangeNative(type, uid)) {
                Log.e(TAG, "registerNotificationRspTrackChangeNative failed!");
            }
        }
    }

    /* getters for some private variables */
    public AvrcpBrowseManager getAvrcpBrowseManager() {
        return mAvrcpBrowseManager;
    }

    // Do not modify without updating the HAL bt_rc.h files.

    // match up with btrc_play_status_t enum of bt_rc.h
    final static int PLAYSTATUS_STOPPED = 0;
    final static int PLAYSTATUS_PLAYING = 1;
    final static int PLAYSTATUS_PAUSED = 2;
    final static int PLAYSTATUS_FWD_SEEK = 3;
    final static int PLAYSTATUS_REV_SEEK = 4;
    final static int PLAYSTATUS_ERROR = 255;

    // match up with btrc_media_attr_t enum of bt_rc.h
    final static int MEDIA_ATTR_TITLE = 1;
    final static int MEDIA_ATTR_ARTIST = 2;
    final static int MEDIA_ATTR_ALBUM = 3;
    final static int MEDIA_ATTR_TRACK_NUM = 4;
    final static int MEDIA_ATTR_NUM_TRACKS = 5;
    final static int MEDIA_ATTR_GENRE = 6;
    final static int MEDIA_ATTR_PLAYING_TIME = 7;

    // match up with btrc_event_id_t enum of bt_rc.h
    final static int EVT_PLAY_STATUS_CHANGED = 1;
    final static int EVT_TRACK_CHANGED = 2;
    final static int EVT_TRACK_REACHED_END = 3;
    final static int EVT_TRACK_REACHED_START = 4;
    final static int EVT_PLAY_POS_CHANGED = 5;
    final static int EVT_BATT_STATUS_CHANGED = 6;
    final static int EVT_SYSTEM_STATUS_CHANGED = 7;
    final static int EVT_APP_SETTINGS_CHANGED = 8;
    final static int EVENT_NOW_PLAYING_CONTENT_CHANGED = 9;
    final static int EVT_AVBL_PLAYERS_CHANGED = 0xa;
    final static int EVT_ADDR_PLAYER_CHANGED = 0xb;
    final static int EVENT_UIDS_CHANGED = 0x0c;

    private native static void classInitNative();
    private native void initNative();
    private native void cleanupNative();
    private native boolean getPlayStatusRspNative(byte[] address, int playStatus, int songLen,
            int songPos);
    private native boolean getElementAttrRspNative(byte[] address, byte numAttr, int[] attrIds,
            String[] textArray);
    private native boolean registerNotificationRspPlayStatusNative(int type, int playStatus);
    private native boolean registerNotificationRspTrackChangeNative(int type, byte[] track);
    private native boolean registerNotificationRspPlayPosNative(int type, int playPos);
    private native boolean setVolumeNative(int volume);
    private native boolean sendPassThroughCommandNative(int keyCode, int keyState);
    private native boolean setAddressedPlayerRspNative(byte[] address, int rspStatus);
    private native boolean setBrowsedPlayerRspNative(byte[] address, int rspStatus, byte depth,
            int numItems, String[] textArray);
    private native boolean mediaPlayerListRspNative(byte[] address, int rsStatus, int uidCounter,
            byte item_type, int numItems, byte[] PlayerTypes, int[] PlayerSubTypes,
            byte[] playStatusValues, short[] FeatureBitMaskValues, String[] textArray);
    private native boolean getFolderItemsRspNative(byte[] address, int rspStatus, short uidCounter,
            byte scope, int numItems, byte[] folderTypes, byte[] playable, byte[] itemTypes,
            byte[] itemUidArray, String[] textArray, int[] AttributesNum, int[] AttributesIds,
            String[] attributesArray);
    private native boolean changePathRspNative(byte[] address, int rspStatus, int numItems);
    private native boolean getItemAttrRspNative(byte[] address, int rspStatus, byte numAttr,
            int[] attrIds, String[] textArray);
    private native boolean playItemRspNative(byte[] address, int rspStatus);
    private native boolean getTotalNumOfItemsRspNative(byte[] address, int rspStatus,
            int uidCounter, int numItems);
    private native boolean searchRspNative(byte[] address, int rspStatus, int uidCounter,
            int numItems);
    private native boolean addToNowPlayingRspNative(byte[] address, int rspStatus);
    private native boolean registerNotificationRspAddrPlayerChangedNative(int type,
        int playerId, int uidCounter);
    private native boolean registerNotificationRspAvalPlayerChangedNative(int type);
    private native boolean registerNotificationRspUIDsChangedNative(int type, int uidCounter);
    private native boolean registerNotificationRspNowPlayingChangedNative(int type);

}
