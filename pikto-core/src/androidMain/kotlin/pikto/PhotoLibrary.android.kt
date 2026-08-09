package pikto

public actual fun PhotoLibrary(): PhotoLibrary = AndroidPhotoLibrary(PiktoAndroid.requireContext())
