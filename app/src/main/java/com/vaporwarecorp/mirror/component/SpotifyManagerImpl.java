package com.vaporwarecorp.mirror.component;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.os.AsyncTask;
import com.robopupu.api.component.AbstractManager;
import com.robopupu.api.dependency.Provides;
import com.robopupu.api.dependency.Scope;
import com.robopupu.api.plugin.Plug;
import com.robopupu.api.plugin.Plugin;
import com.robopupu.api.plugin.PluginBus;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.player.*;
import com.spotify.sdk.android.player.Player.InitializationObserver;
import com.vaporwarecorp.mirror.app.MirrorAppScope;
import com.vaporwarecorp.mirror.event.ResetEvent;
import com.vaporwarecorp.mirror.event.SpotifyPlaybackEvent;
import com.vaporwarecorp.mirror.event.SpotifyTrackEvent;
import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.Track;
import kaaes.spotify.webapi.android.models.Tracks;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;
import timber.log.Timber;

import java.util.List;

import static android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS;
import static android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS;
import static solid.collectors.ToList.toList;
import static solid.stream.Stream.stream;

@Plugin
public class SpotifyManagerImpl
        extends AbstractManager
        implements SpotifyManager, PlayerNotificationCallback, Callback<Track>, PlayerStateCallback {
// ------------------------------ FIELDS ------------------------------

    @Plug
    AppManager mAppManager;
    @Plug
    EventManager mEventManager;

    private String mClientId;
    private String mClientRedirectUri;
    private Context mContext;
    private PlayerState mCurrentPlayerState;
    private List<Track> mCurrentTracks;
    private AsyncTask<Object, Void, Void> mLoginAsyncTask = new AsyncTask<Object, Void, Void>() {
        protected Void doInBackground(Object... params) {
            final Integer resultCode = (Integer) params[0];
            final Intent intent = (Intent) params[1];
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            if (response.getType() == AuthenticationResponse.Type.TOKEN) {
                mPlayerConfig = new Config(mContext, response.getAccessToken(), mClientId);
            }
            return null;
        }
    };
    private Player mPlayer;
    private Config mPlayerConfig;
    private SpotifyService mService;
    private MediaSession mSession;

// --------------------------- CONSTRUCTORS ---------------------------

    @Scope(MirrorAppScope.class)
    @Provides(SpotifyManager.class)
    public SpotifyManagerImpl() {
    }

// ------------------------ INTERFACE METHODS ------------------------


// --------------------- Interface Callback ---------------------

    @Override
    public void success(Track track, Response response) {
        if (track != null) {
            mPlayer.getPlayerState(this);
            mEventManager.post(new SpotifyTrackEvent(track));
        }
    }

    @Override
    public void failure(RetrofitError error) {
    }

// --------------------- Interface PlayerNotificationCallback ---------------------

    @Override
    public void onPlaybackEvent(EventType eventType, PlayerState playerState) {
        mCurrentPlayerState = playerState;

        int lastPosition = playerState.positionInMs;
        if (eventType == EventType.TRACK_CHANGED) {
            //String trackId = playerState.trackUri.replaceAll("spotify:track:", "");
            //mService.getTrack(trackId, this);
        }

        mEventManager.post(new SpotifyPlaybackEvent(lastPosition, eventType));
        if (eventType == EventType.END_OF_CONTEXT) {
            stop();
        }
    }

    @Override
    public void onPlaybackError(ErrorType errorType, String s) {
    }

// --------------------- Interface PlayerStateCallback ---------------------

    @Override
    public void onPlayerState(PlayerState playerState) {
        onPlaybackEvent(mCurrentPlayerState.playing ? EventType.PLAY : EventType.PAUSE, playerState);
    }

// --------------------- Interface PluginComponent ---------------------

    @Override
    public void onPlugged(PluginBus bus) {
        mContext = mAppManager.getAppContext();
        mClientId = mAppManager.getApplicationProperties().getProperty(CLIENT_ID);
        mClientRedirectUri = mAppManager.getApplicationProperties().getProperty(CLIENT_REDIRECT_URI);
        mService = new SpotifyApi().getService();
    }

// --------------------- Interface SpotifyManager ---------------------

    @Override
    public void authenticate(Activity activity) {
        AuthenticationRequest request = new AuthenticationRequest
                .Builder(mClientId, AuthenticationResponse.Type.TOKEN, mClientRedirectUri)
                .setScopes(new String[]{"user-read-private", "streaming"})
                .build();
        AuthenticationClient.openLoginActivity(activity, REQUEST_CODE, request);
    }

    @Override
    public void play(List<String> trackUris, Listener listener) {
        if (trackUris == null || trackUris.isEmpty()) {
            return;
        }

        String trackIds = stream(trackUris)
                .map(t -> t.replaceAll("spotify:track:", ""))
                .reduce((t, u) -> t + "," + u)
                .get();
        mService.getTracks(trackIds, new Callback<Tracks>() {
            @Override
            public void success(Tracks tracks, Response response) {
                play(tracks);
                if (listener != null) {
                    listener.onTracksLoaded(tracks.tracks);
                }
            }

            @Override
            public void failure(RetrofitError error) {
                Timber.e(error, "Problem retrieving the tracks information");
            }
        });
    }

    @Override
    public void processAuthentication(int resultCode, Intent data) {
        mLoginAsyncTask.execute(resultCode, data);
    }

    @Override
    public void stop() {
        if (mSession != null && mSession.isActive()) {
            mSession.setActive(false);
        }
        Spotify.destroyPlayer(this);
        mEventManager.post(new ResetEvent());
    }

// -------------------------- OTHER METHODS --------------------------

    public void togglePlay() {
        if (mCurrentPlayerState.playing) {
            mPlayer.pause();
        } else {
            mPlayer.resume();
        }
    }

    private void play(Tracks tracks) {
        mCurrentPlayerState = new PlayerState();
        mCurrentTracks = tracks.tracks;

        Spotify.getPlayer(mPlayerConfig, this, new InitializationObserver() {
            @Override
            public void onInitialized(Player player) {
                // create a session so that a now playing card is active on the homescreen
                mSession = new MediaSession(mContext, "SpotifySession");
                //mSession.setCallback(new SpotifyMediaSessionCallback(player));
                mSession.setFlags(FLAG_HANDLES_TRANSPORT_CONTROLS | FLAG_HANDLES_MEDIA_BUTTONS);
                //mSession.setSessionActivity(getSessionActivity());

                mPlayer = player;
                mPlayer.addPlayerNotificationCallback(SpotifyManagerImpl.this);
                mPlayer.play(stream(mCurrentTracks).map(t -> t.uri).collect(toList()));
            }

            @Override
            public void onError(Throwable throwable) {
                Timber.e(throwable, "Could not initialize player: %s", throwable.getMessage());
            }
        });
    }
}