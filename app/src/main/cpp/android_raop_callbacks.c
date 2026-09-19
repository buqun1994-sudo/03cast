/*
 * Implements raop_callbacks_t by forwarding to Java/Kotlin via JNI.
 * All callbacks fire from RAOP's internal pthreads, so we AttachCurrentThread.
 */

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <stdint.h>
#include <android/log.h>
#include "android_raop_callbacks.h"
#include "audio_engine.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JNIEnv *_get_env(android_callback_ctx_t *ctx) {
    JNIEnv *env = NULL;
    int status = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
    }
    /* Clear any pending exception from a previous callback on this thread,
       otherwise JNI calls like NewByteArray will fatally abort. */
    if (env && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    return env;
}

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj) {
    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->callback_obj = (*env)->NewGlobalRef(env, callback_obj);
    ctx->h265_enabled = 1;
    ctx->require_pin = 0;
    ctx->registered_count = 0;
    ctx->audio_engine = NULL;
    memset(ctx->registered_keys, 0, sizeof(ctx->registered_keys));

    pthread_mutex_init(&ctx->playback_info_lock, NULL);
    ctx->playback_position = 0.0;
    /* -1.0 is the video finished sentinel, reserved for _video_stop */
    ctx->playback_duration = 0.0;
    ctx->playback_rate = 0.0f;
    ctx->playback_play_when_ready = 0;
    ctx->playback_ready = 0;
    ctx->playback_end_projection_held = 0;

    jclass cls = (*env)->GetObjectClass(env, callback_obj);
    ctx->on_video_data = (*env)->GetMethodID(env, cls, "onVideoData", "(J[BJZ)V");
    ctx->on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(IIZ)V");
    ctx->on_video_size = (*env)->GetMethodID(env, cls, "onVideoSize", "(JFFFF)V");
    ctx->on_volume_change = (*env)->GetMethodID(env, cls, "onVolumeChange", "(F)V");
    ctx->on_client_volume = (*env)->GetMethodID(env, cls, "onClientVolume", "()F");
    ctx->on_audio_teardown = (*env)->GetMethodID(env, cls, "onAudioTeardown", "()V");
    ctx->on_mirror_video_running = (*env)->GetMethodID(env, cls, "onMirrorVideoRunning", "(JZ)V");
    ctx->on_conn_init = (*env)->GetMethodID(env, cls, "onConnectionInit", "()V");
    ctx->on_conn_destroy = (*env)->GetMethodID(env, cls, "onConnectionDestroy", "()V");
    ctx->on_conn_reset = (*env)->GetMethodID(env, cls, "onConnectionReset", "(I)V");
    ctx->on_display_pin = (*env)->GetMethodID(env, cls, "onDisplayPin", "(Ljava/lang/String;)V");
    ctx->on_metadata = (*env)->GetMethodID(env, cls, "onMetadata", "([B)V");
    ctx->on_coverart = (*env)->GetMethodID(env, cls, "onCoverArt", "([B)V");
    ctx->on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(JJJ)V");
    ctx->on_dacp_id = (*env)->GetMethodID(env, cls, "onDacpId", "(Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_video_play = (*env)->GetMethodID(env, cls, "onVideoPlay", "(Ljava/lang/String;F)V");
    ctx->on_video_item_play = (*env)->GetMethodID(env, cls, "onVideoItemPlay", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;F)V");
    ctx->on_video_remove = (*env)->GetMethodID(env, cls, "onVideoItemRemoved", "(Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_video_scrub = (*env)->GetMethodID(env, cls, "onVideoScrub", "(F)V");
    ctx->on_video_rate = (*env)->GetMethodID(env, cls, "onVideoRate", "(F)V");
    ctx->on_video_stop = (*env)->GetMethodID(env, cls, "onVideoStop", "()V");
    ctx->on_video_session_poll = (*env)->GetMethodID(env, cls, "onVideoSessionPoll", "()V");
    (*env)->DeleteLocalRef(env, cls);
}

void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env) {
    if (ctx->callback_obj) {
        (*env)->DeleteGlobalRef(env, ctx->callback_obj);
        ctx->callback_obj = NULL;
    }
    for (int i = 0; i < ctx->registered_count; i++) {
        free(ctx->registered_keys[i]);
        ctx->registered_keys[i] = NULL;
    }
    ctx->registered_count = 0;
    pthread_mutex_destroy(&ctx->playback_info_lock);
}

void android_callbacks_update_playback_info(android_callback_ctx_t *ctx, double position,
                                             double duration, float rate, int ready,
                                             int play_when_ready) {
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->playback_position = position;
    ctx->playback_duration = duration;
    ctx->playback_rate = rate;
    ctx->playback_play_when_ready = play_when_ready;
    ctx->playback_ready = ready;
    if (duration != -1.0) {
        ctx->playback_end_projection_held = 0;
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

void android_callbacks_project_playback_end(android_callback_ctx_t *ctx) {
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->playback_position = 0.0;
    ctx->playback_duration = -1.0;
    ctx->playback_rate = 0.0f;
    ctx->playback_play_when_ready = 0;
    ctx->playback_ready = 0;
    ctx->playback_end_projection_held = 1;
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

/* --- RAOP callback implementations --- */

static void _audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    if (!ctx->audio_engine || !data->data || data->data_len <= 0) return;
    audio_engine_decode(ctx->audio_engine, data->data, data->data_len,
                        (int)data->ct, (int64_t)data->ntp_time_local);
}

static void _video_process_with_token(void *cls, uint64_t stream_token,
                                      raop_ntp_t *ntp, video_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data->data || data->data_len <= 0) return;

    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (jbyte *)data->data);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_data,
                           (jlong)stream_token, arr,
                           (jlong)data->ntp_time_local, (jboolean)data->is_h265);
    (*env)->DeleteLocalRef(env, arr);
}

/* raop_init still requires the legacy callback slot to be populated. The
 * mirror transport prefers the tokenized extension below, while this wrapper
 * keeps the upstream callback contract valid for validation and fallback. */
static void _video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    _video_process_with_token(cls, 0, ntp, data);
}

static void _video_process_ex(void *cls, uint64_t stream_token,
                              raop_ntp_t *ntp, video_decode_struct *data) {
    _video_process_with_token(cls, stream_token, ntp, data);
}

static void _conn_init(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    /* A projected finish must survive the player's own HLS fetch connections. */
    pthread_mutex_lock(&ctx->playback_info_lock);
    if (ctx->playback_duration == -1.0 && !ctx->playback_end_projection_held) {
        ctx->playback_position = 0.0;
        ctx->playback_duration = 0.0;
        ctx->playback_rate = 0.0f;
        ctx->playback_play_when_ready = 0;
        ctx->playback_ready = 0;
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_init);
}

static void _conn_destroy(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_destroy);
}

static void _conn_reset(void *cls, int reason) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_reset, (jint)reason);
}

static void _audio_set_volume(void *cls, float volume) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_volume_change, (jfloat)volume);
}

static void _audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                               bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_format,
                           (jint)*ct, (jint)*spf, (jboolean)*usingScreen);
}

static void _video_report_size_with_token(void *cls, uint64_t stream_token,
                                           float *w_src, float *h_src,
                                           float *w, float *h) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    LOGI("AirPlay RTP video size: source=%0.0fx%0.0f display=%0.0fx%0.0f",
         *w_src, *h_src, *w, *h);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_size,
                           (jlong)stream_token, (jfloat)*w_src, (jfloat)*h_src,
                           (jfloat)*w, (jfloat)*h);
}

static void _video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h) {
    _video_report_size_with_token(cls, 0, w_src, h_src, w, h);
}

static void _video_report_size_ex(void *cls, uint64_t stream_token,
                                  float *w_src, float *h_src, float *w, float *h) {
    _video_report_size_with_token(cls, stream_token, w_src, h_src, w, h);
}

static void _display_pin(void *cls, char *pin) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    
    /* certain senders trigger pair-pin-start even when we advertise no auth */
    if (!ctx->require_pin) return;

    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jpin = (*env)->NewStringUTF(env, pin);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_display_pin, jpin);
    (*env)->DeleteLocalRef(env, jpin);
}

/* Stubs for less critical callbacks */
static void _noop(void *cls) { (void)cls; }
static void _noop_teardown(void *cls, bool *a, bool *b) { (void)cls; (void)a; (void)b; }
static void _video_pause(void *cls) { LOGI("video_pause"); }
static void _video_resume(void *cls) { LOGI("video_resume"); }
static void _conn_feedback(void *cls) { (void)cls; }
static void _video_stop(void *cls);
static void _video_reset(void *cls, reset_type_t t) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_reset %d", t);
    if (t == RESET_TYPE_HLS_SHUTDOWN || t == RESET_TYPE_HLS_EOS) {
        _video_stop(cls);
    }
    /* A TCP connection closing is not a media Stop, including while paused.
       The queue owns natural end, and POST /stop owns explicit termination. */
    if (t == RESET_TYPE_HLS_SHUTDOWN && ctx->raop) {
        raop_remove_hls_connections(ctx->raop);
    }
}
static void _audio_flush(void *cls) { LOGI("audio_flush"); }

static void _audio_stop_coverart_rendering(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("audio_teardown");
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_teardown);
}
static void _video_flush(void *cls) { LOGI("video_flush"); }
static double _audio_set_client_volume(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return 0.0;
    return (double)(*env)->CallFloatMethod(env, ctx->callback_obj, ctx->on_client_volume);
}
static void _audio_set_metadata(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_metadata, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_set_coverart(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_coverart, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jdacp = (*env)->NewStringUTF(env, dacp_id ? dacp_id : "");
    jstring jremote = (*env)->NewStringUTF(env, active_remote ? active_remote : "");
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_dacp_id, jdacp, jremote);
    (*env)->DeleteLocalRef(env, jdacp);
    (*env)->DeleteLocalRef(env, jremote);
}

/* HLS requests expose the same credentials in the opposite callback order. */
static void _export_dacp(void *cls, const char *active_remote, const char *dacp_id) {
    _audio_remote_control_id(cls, dacp_id, active_remote);
}

static void _audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !start || !curr || !end) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_progress,
                           (jlong)*start, (jlong)*curr, (jlong)*end);
}

static void _mirror_video_running(void *cls, uint64_t stream_token, bool running) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    LOGI("mirror running: %d stream_token=%llu", running,
         (unsigned long long)stream_token);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_mirror_video_running,
                           (jlong)stream_token, (jboolean)running);
}
static void _register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    (void)device_id; (void)name;
    if (ctx->registered_count >= 16) return;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return;
    }
    ctx->registered_keys[ctx->registered_count++] = strdup(pk_str);
    LOGI("registered client pk (slot %d)", ctx->registered_count);
}

static bool _check_register(void *cls, const char *pk_str) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return true;
    }
    return false;
}

/* --- AirPlay Video (HLS) playback callbacks --- */

static void _video_play_with_uuid(void *cls, const char *session_id, const char *playback_uuid,
                                  const char *location, const float start_position) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_play: item=%s @ %.2fs", playback_uuid ? playback_uuid : "(none)", start_position);
    android_callbacks_update_playback_info(ctx, start_position, 0.0, 0.0f, 0, 1);
    JNIEnv *env = _get_env(ctx);
    if (!env || !location) return;
    jstring jsession = (*env)->NewStringUTF(env, session_id ? session_id : "");
    jstring juuid = (*env)->NewStringUTF(env, playback_uuid ? playback_uuid : "");
    jstring jloc = (*env)->NewStringUTF(env, location);
    if (ctx->on_video_item_play) {
        (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_item_play,
                               jsession, juuid, jloc, (jfloat)start_position);
    } else if (ctx->on_video_play) {
        (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_play,
                               jloc, (jfloat)start_position);
    }
    (*env)->DeleteLocalRef(env, juuid);
    (*env)->DeleteLocalRef(env, jloc);
    (*env)->DeleteLocalRef(env, jsession);
    /* Return immediately: this same HTTP loop must serve Media3's HLS reads.
       Playback readiness and duration are published by subsequent info polls. */
}

static void _video_play(void *cls, const char *location, const float start_position) {
    _video_play_with_uuid(cls, NULL, NULL, location, start_position);
}

static void _video_play_ex(void *cls, const char *session_id, const char *playback_uuid,
                           const char *location, const float start_position) {
    _video_play_with_uuid(cls, session_id, playback_uuid, location, start_position);
}

static void _video_remove(void *cls, const char *session_id, const char *playback_uuid) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !playback_uuid) return;
    jstring jsession = (*env)->NewStringUTF(env, session_id ? session_id : "");
    jstring juuid = (*env)->NewStringUTF(env, playback_uuid);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_remove, jsession, juuid);
    (*env)->DeleteLocalRef(env, jsession);
    (*env)->DeleteLocalRef(env, juuid);
}

static int _video_cache_rank(void *cls, const char *uuid) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    int rank = (uuid && !strcmp(uuid, ctx->video_current_uuid)) ? INT32_MAX :
        ((uuid && !strcmp(uuid, ctx->video_eviction_uuid)) ? 0 : 1);
    pthread_mutex_unlock(&ctx->playback_info_lock);
    return rank;
}

static void _video_scrub(void *cls, const float position) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_scrub, (jfloat)position);
}

static void _video_rate(void *cls, const float rate) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_rate, (jfloat)rate);
}

static void _video_stop(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    android_callbacks_update_playback_info(ctx, 0.0, -1.0, 0.0f, 0, 0);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_stop);
}

/* httpd thread: reads the kotlin-pushed snapshot, never calls into the player */
static void _video_acquire_playback_info(void *cls, playback_info_t *info) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    info->position = ctx->playback_position;
    info->duration = ctx->playback_duration;
    info->rate = ctx->playback_rate;
    info->ready_to_play = ctx->playback_ready;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    info->playback_likely_to_keep_up = true;
    info->seek_start = 0.0;
    info->seek_duration = ctx->playback_duration > 0.0 ? ctx->playback_duration : 0.0;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    /* polls are the earliest video-channel signal, starting ~1s before /play */
    JNIEnv *env = _get_env(ctx);
    if (env) {
        (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_session_poll);
    }
}

static float _video_playlist_remove(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    double position = ctx->playback_position;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    return (float) position;
}

static int _video_set_codec(void *cls, video_codec_t codec) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_set_codec: %d (h265_enabled=%d)", codec, ctx->h265_enabled);
    if (codec == VIDEO_CODEC_H265 && !ctx->h265_enabled) return -1;
    return 0;
}

void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx) {
    memset(cbs, 0, sizeof(raop_callbacks_t));
    cbs->cls = ctx;

    cbs->audio_process = _audio_process;
    cbs->video_process = _video_process;
    cbs->video_process_ex = _video_process_ex;
    cbs->video_pause = _video_pause;
    cbs->video_resume = _video_resume;
    cbs->conn_feedback = _conn_feedback;
    cbs->conn_reset = _conn_reset;
    cbs->video_reset = _video_reset;
    cbs->conn_init = _conn_init;
    cbs->conn_destroy = _conn_destroy;
    cbs->conn_teardown = _noop_teardown;
    cbs->audio_flush = _audio_flush;
    cbs->video_flush = _video_flush;
    cbs->audio_set_client_volume = _audio_set_client_volume;
    cbs->audio_set_volume = _audio_set_volume;
    cbs->audio_set_metadata = _audio_set_metadata;
    cbs->audio_set_coverart = _audio_set_coverart;
    cbs->audio_stop_coverart_rendering = _audio_stop_coverart_rendering;
    cbs->audio_remote_control_id = _audio_remote_control_id;
    cbs->export_dacp = _export_dacp;
    cbs->audio_set_progress = _audio_set_progress;
    cbs->audio_get_format = _audio_get_format;
    cbs->video_report_size = _video_report_size;
    cbs->video_report_size_ex = _video_report_size_ex;
    cbs->mirror_video_running_ex = _mirror_video_running;
    cbs->display_pin = _display_pin;
    cbs->video_set_codec = _video_set_codec;
    cbs->on_video_play = _video_play;
    cbs->on_video_play_ex = _video_play_ex;
    cbs->on_video_remove = _video_remove;
    cbs->on_video_cache_rank = _video_cache_rank;
    cbs->on_video_scrub = _video_scrub;
    cbs->on_video_rate = _video_rate;
    cbs->on_video_stop = _video_stop;
    cbs->on_video_acquire_playback_info = _video_acquire_playback_info;
    cbs->on_video_playlist_remove = _video_playlist_remove;
    if (ctx->require_pin) {
        cbs->check_register = _check_register;
        cbs->register_client = _register_client;
    }
}
