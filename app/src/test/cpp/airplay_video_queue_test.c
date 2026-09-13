/* Exercises the production AirPlay playlist store without Android or a sender. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "raop.h"
#include "airplay_video.h"

static airplay_video_t *video(void) {
    airplay_video_t *result = airplay_video_init((raop_t *)(uintptr_t)1, 7000, NULL);
    assert(result);
    set_playback_uuid(result, "11111111-2222-3333-4444-555555555555", 36);
    return result;
}

static void local_uri_isolation(void) {
    airplay_video_t *a = video(), *b = video();
    assert(strcmp(get_uri_local_prefix(a), get_uri_local_prefix(b)));
    char url[256];
    const char *path = strchr(strstr(get_uri_local_prefix(a), "://") + 3, '/');
    snprintf(url, sizeof(url), "%s/master.m3u8", path);
    const char *relative = NULL;
    assert(match_local_video_uri(a, url, &relative));
    assert(!strcmp(relative, "/master.m3u8"));
    assert(!match_local_video_uri(b, url, NULL));
    assert(!match_local_video_uri(a, "/master.m3u8", NULL));
    snprintf(url, sizeof(url), "%s0/master.m3u8", path);
    assert(!match_local_video_uri(a, url, NULL));
    airplay_video_destroy(a);
    a = video();
    assert(!match_local_video_uri(a, url, NULL));
    airplay_video_destroy(a); airplay_video_destroy(b);
}

static void fcup_exact_request_ownership(void) {
    airplay_video_t *a = video(), *b = video();
    const char *url = "https://sender/shared/master.m3u8";
    int first = prepare_fcup_request(a, url), second = prepare_fcup_request(b, url);
    assert(first != second);
    assert(!consume_fcup_response(a, second, url));
    assert(!consume_fcup_response(a, first, "https://sender/wrong/master.m3u8"));
    assert(consume_fcup_response(b, second, url));
    assert(consume_fcup_response(a, first, url));
    assert(!consume_fcup_response(a, first, url));
    airplay_video_destroy(a);
    a = video();
    assert(!consume_fcup_response(a, first, url));
    for (int i = 0; i < 32; i++) assert(prepare_fcup_request(a, url) > 0);
    assert(prepare_fcup_request(a, url) == -1);
    airplay_video_destroy(a); airplay_video_destroy(b);
}

static void master_and_variant_keep_item_identity(void) {
    airplay_video_t *a = video(), *b = video();
    const char *remote = "https://sender/video";
    char master[] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nhttps://sender/video/one.m3u8\n";
    char *rewritten = adjust_master_playlist(master, (int)strlen(master), remote, get_uri_local_prefix(a));
    assert(strstr(rewritten, get_uri_local_prefix(a)));
    assert(!strstr(rewritten, get_uri_local_prefix(b)));
    free(rewritten);
    set_uri_prefix(a, remote, strlen(remote));
    char **uris = calloc(1, sizeof(char *));
    uris[0] = strdup("https://sender/video/one.m3u8");
    create_media_data_store(a, uris, 1); free(uris);
    char *playlist = strdup("#EXTM3U\n#EXTINF:2,\nhttps://cdn/clip.ts\n#EXT-X-ENDLIST\n");
    int count = 1; float duration = 2; bool endlist = true;
    assert(store_media_playlist(a, playlist, &count, &duration, &endlist, 0) == 0);
    bool fetch = false; const char *remote_uri = NULL;
    char *copy = get_media_playlist(a, &count, &duration, "/one.m3u8", &fetch, &remote_uri);
    assert(copy && strstr(copy, "clip.ts") && !fetch);
    free(copy);
    assert(!get_media_playlist(a, &count, &duration, "one.m3u8", &fetch, &remote_uri));
    airplay_video_destroy(a); airplay_video_destroy(b);
}

int main(void) {
    local_uri_isolation();
    fcup_exact_request_ownership();
    master_and_variant_keep_item_identity();
    puts("AirPlay queue native checks: 3 groups passed");
    return 0;
}
