package com.cornholio.ncs;

import android.service.notification.NotificationListenerService;

/**
 * Only here so Android lets the wallpaper ask which song is playing (the song
 * letters setting). It reads no notifications and keeps nothing.
 */
public class SongListener extends NotificationListenerService {
}
