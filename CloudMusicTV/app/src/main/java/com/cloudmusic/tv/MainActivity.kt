package com.cloudmusic.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.cloudmusic.tv.ui.CloudMusicApp
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicTvTheme

/**
 * Single activity host for the whole app.
 *
 * The launcher icon opens this activity through both the LAUNCHER and the
 * LEANBACK_LAUNCHER intent filters declared in AndroidManifest.xml.
 *
 * Screen switching (login / main / settings) lives in [CloudMusicApp].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CloudMusicTvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = CloudMusicColors.Background,
                        contentColor = CloudMusicColors.OnDark,
                    ),
                ) {
                    CloudMusicApp()
                }
            }
        }
    }
}
