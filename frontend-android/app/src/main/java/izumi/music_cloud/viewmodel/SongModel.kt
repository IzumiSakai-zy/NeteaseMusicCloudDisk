package izumi.music_cloud.viewmodel

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.MutableLiveData
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import izumi.music_cloud.App
import izumi.music_cloud.callback.DownloadCallBack
import izumi.music_cloud.error.Error
import izumi.music_cloud.global.GlobalConst
import izumi.music_cloud.global.GlobalUtil.getFilePathBySongId
import izumi.music_cloud.global.GlobalUtil.musicExists
import izumi.music_cloud.recycler.SongData
import izumi.music_cloud.retrofit.SongService
import java.io.File
import kotlin.concurrent.thread


class SongModel {

    private val disposable = CompositeDisposable()

    private val handler = Handler(Looper.getMainLooper())

    private fun updateDownloadStatus(songList: List<SongData>) {
        for (song in songList) {
            val id = song.id ?: continue
            if (id.musicExists()) song.downloaded = true
        }
    }


    fun getSongList(_songList: MutableLiveData<List<SongData>>, _error: MutableLiveData<Error?>) {
        SongService.getSongList()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnError {
                Log.d(GlobalConst.LOG_TAG, "resetSongList on complete")
                _error.value = Error.GET_SONG_LIST_ERROR
            }
            .subscribe {
                Log.d(GlobalConst.LOG_TAG, "resetSongList on next")
                updateDownloadStatus(it)
                _songList.value = it
            }.let { disposable.add(it) }
    }

    fun downloadSong(
        _songList: MutableLiveData<List<SongData>>,
        _error: MutableLiveData<Error?>,
        index: Int,
        callBack: DownloadCallBack? = null
    ) {
        val songId = _songList.value?.get(index)?.id ?: return
        if (songId.musicExists()) return

        SongService.downloadSong(songId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnError {
                Log.d(GlobalConst.LOG_TAG, "downloadSong do on error")
                _error.value = Error.DOWNLOAD_SONG_ERROR
            }
            .subscribe {
                thread {
                    Log.d(GlobalConst.LOG_TAG, "downloadSong do on next")

                    val file = File(songId.getFilePathBySongId()).apply {
                        if (this.exists()) this.delete()
                        this.createNewFile()
                    }

                    val uri = FileProvider.getUriForFile(
                        App.context,
                        GlobalConst.FILE_PROVIDER_AUTHORITIES,
                        file
                    )

                    val input = it.body()?.byteStream() ?: return@thread

                    Log.d(GlobalConst.LOG_TAG, "downloadSong start")

                    App.context.contentResolver?.openOutputStream(uri).use { output ->
                        output ?: return@thread
                        val buffer = ByteArray(4 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            Log.d(GlobalConst.LOG_TAG, "downloadSong downloading")
                            output.write(buffer, 0, read)
                            callBack?.onDownloading(50)
                        }
                        output.flush()
                        callBack?.onDownloading(100)
                    }
                    Log.d(GlobalConst.LOG_TAG, "downloadSong finish")

                    handler.sendMessage(Message.obtain(handler) {
                        Log.d(GlobalConst.LOG_TAG, "downloadSong handler do")
                        _songList.value?.apply {
                            get(index).downloaded = true
                            _songList.value = this
                        }
                        callBack?.onComplete(index)
                    })
                }
            }.let { disposable.add(it) }
    }
}