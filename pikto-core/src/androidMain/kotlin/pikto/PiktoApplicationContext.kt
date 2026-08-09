package pikto

import android.content.Context

/** The application context captured by [PiktoInitializer]. See [InternalPiktoApi]. */
@InternalPiktoApi
public fun piktoApplicationContext(): Context = PiktoAndroid.requireContext()
