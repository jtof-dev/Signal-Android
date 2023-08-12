package org.mycrimes.insecuretests.giph.mp4;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.signal.paging.PagedDataSource;
import org.mycrimes.insecuretests.BuildConfig;
import org.mycrimes.insecuretests.dependencies.ApplicationDependencies;
import org.mycrimes.insecuretests.giph.model.GiphyImage;
import org.mycrimes.insecuretests.giph.model.GiphyResponse;
import org.mycrimes.insecuretests.net.ContentProxySelector;
import org.mycrimes.insecuretests.util.JsonUtils;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Data source for GiphyImages.
 */
final class GiphyMp4PagedDataSource implements PagedDataSource<String, GiphyImage> {

  private static final Uri BASE_GIPHY_URI = Uri.parse("https://api.giphy.com/v1/gifs/")
                                               .buildUpon()
                                               .appendQueryParameter("api_key", BuildConfig.GIPHY_API_KEY)
                                               .build();

  private static final Uri TRENDING_URI = BASE_GIPHY_URI.buildUpon()
                                                        .appendPath("trending")
                                                        .build();

  private static final Uri SEARCH_URI = BASE_GIPHY_URI.buildUpon()
                                                      .appendPath("search")
                                                      .build();


  private static final String TAG = Log.tag(GiphyMp4PagedDataSource.class);

  private final String       searchString;
  private final OkHttpClient client;

  GiphyMp4PagedDataSource(@Nullable String searchQuery) {
    this.searchString = Optional.ofNullable(searchQuery).map(String::trim).orElse("");
    this.client       = ApplicationDependencies.getOkHttpClient().newBuilder().proxySelector(new ContentProxySelector()).build();
  }

  @Override
  public int size() {
    try {
      GiphyResponse response = performFetch(0, 1);

      return response.getPagination().getTotalCount();
    } catch (IOException | NullPointerException e) {
      Log.w(TAG, "Failed to get size", e);
      return 0;
    }
  }

  @Override
  public @NonNull List<GiphyImage> load(int start, int length, int totalSize, @NonNull CancellationSignal cancellationSignal) {
    try {
      Log.d(TAG, "Loading from " + start + " to " + (start + length));
      return new LinkedList<>(performFetch(start, length).getData());
    } catch (IOException | NullPointerException e) {
      Log.w(TAG, "Failed to load content", e);
      return new LinkedList<>();
    }
  }

  @Override
  public String getKey(@NonNull GiphyImage giphyImage) {
    return giphyImage.getGifUrl();
  }

  @Override
  public @Nullable GiphyImage load(String url) {
    throw new UnsupportedOperationException("Not implemented!");
  }

  private @NonNull GiphyResponse performFetch(int start, int length) throws IOException {
    String url;

    if (TextUtils.isEmpty(searchString)) url = getTrendingUrl(start, length);
    else                                 url = getSearchUrl(start, length, searchString);

    Request request = new Request.Builder().url(url).build();

    try (Response response = client.newCall(request).execute()) {

      if (!response.isSuccessful()) {
        throw new IOException("Unexpected code " + response);
      }

      if (response.body() == null) {
        throw new IOException("Response body was not present");
      }

      return JsonUtils.fromJson(response.body().byteStream(), GiphyResponse.class);
    }
  }

  private String getTrendingUrl(int start, int length) {
    return TRENDING_URI.buildUpon()
                       .appendQueryParameter("offset", String.valueOf(start))
                       .appendQueryParameter("limit", String.valueOf(length))
                       .build()
                       .toString();
  }

  private String getSearchUrl(int start, int length, @NonNull String query) {
    return SEARCH_URI.buildUpon()
                     .appendQueryParameter("offset", String.valueOf(start))
                     .appendQueryParameter("limit", String.valueOf(length))
                     .appendQueryParameter("q", query)
                     .build()
                     .toString();
  }
}