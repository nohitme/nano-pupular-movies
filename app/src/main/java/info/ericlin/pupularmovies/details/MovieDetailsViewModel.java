package info.ericlin.pupularmovies.details;

import android.app.Application;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.palette.graphics.Palette;
import com.bumptech.glide.request.FutureTarget;
import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import info.ericlin.moviedb.Identifiable;
import info.ericlin.moviedb.MovieDbService;
import info.ericlin.moviedb.glide.MovieDbImagePath;
import info.ericlin.moviedb.model.MovieReview;
import info.ericlin.moviedb.model.MovieVideo;
import info.ericlin.moviedb.model.MovieWithDetails;
import info.ericlin.pupularmovies.PreferencesManager;
import info.ericlin.pupularmovies.R;
import info.ericlin.pupularmovies.dagger.GlideApp;
import info.ericlin.pupularmovies.database.AppDatabase;
import info.ericlin.pupularmovies.database.ModelConverter;
import info.ericlin.pupularmovies.database.MovieDao;
import info.ericlin.pupularmovies.database.MovieEntity;
import info.ericlin.pupularmovies.factory.ViewModelFactory;
import info.ericlin.util.ExecutorProvider;
import io.reactivex.Completable;
import io.reactivex.Single;
import java.util.ArrayList;
import java.util.List;
import timber.log.Timber;

/** View model for {@link info.ericlin.pupularmovies.DetailActivity} */
@AutoFactory(implementing = ViewModelFactory.class)
public class MovieDetailsViewModel extends AndroidViewModel {

  private static final String SITE_YOUTUBE = "YouTube";

  @NonNull private final MovieDbService movieDbService;
  @NonNull private final ModelConverter modelConverter;
  @NonNull private final PreferencesManager preferencesManager;
  @NonNull private final ExecutorProvider executorProvider;
  @NonNull private final MovieDao movieDao;

  private final MutableLiveData<Integer> movieIdLiveData = new MutableLiveData<>();
  private final LiveData<List<Identifiable>> detailsListLiveData =
      Transformations.switchMap(movieIdLiveData, this::getDetailsListItem);
  private final LiveData<Boolean> isFavoriteLiveData =
      Transformations.switchMap(movieIdLiveData, this::isMovieSaved);

  // we only want youtube videos
  private Predicate<MovieVideo> youTubeFilter =
      video -> video != null && SITE_YOUTUBE.equalsIgnoreCase(video.site());

  @Nullable
  private MovieWithDetails movie;

  MovieDetailsViewModel(
      @NonNull Application application,
      @NonNull @Provided MovieDbService movieDbService,
      @NonNull @Provided AppDatabase appDatabase,
      @NonNull @Provided ModelConverter modelConverter,
      @NonNull @Provided PreferencesManager preferencesManager,
      @NonNull @Provided ExecutorProvider executorProvider) {
    super(application);
    this.movieDbService = movieDbService;
    this.modelConverter = modelConverter;
    this.preferencesManager = preferencesManager;
    this.executorProvider = executorProvider;
    movieDao = appDatabase.movieDao();
  }

  public void setMovieId(int movieId) {
    if (!Objects.equal(movieId, movieIdLiveData.getValue())) {
      movieIdLiveData.postValue(movieId);
    }
  }

  public LiveData<List<Identifiable>> getDetailsListLiveData() {
    return detailsListLiveData;
  }

  public LiveData<Boolean> isFavoriteLiveData() {
    return isFavoriteLiveData;
  }

  private LiveData<List<Identifiable>> getDetailsListItem(int movieId) {
    final Single<MovieWithDetails> movie = movieDbService
        .getMovieWithDetails(movieId)
        .doOnSuccess(m -> this.movie = m)
        .doOnError(t -> this.movie = null);
    final Single<Palette> palette = movie.flatMap(this::extractPalette);
    final Single<MovieSwatch> moviePalette =
        movie.zipWith(palette, (m, p) -> MovieSwatch.create(m, p.getDominantSwatch()));
    Single<List<Identifiable>> items = moviePalette.map(this::convertToRecyclerViewItems);

    return LiveDataReactiveStreams.fromPublisher(items.toFlowable());
  }

  private Single<Palette> extractPalette(@NonNull MovieWithDetails movie) {

    final FutureTarget<Bitmap> futureTarget =
        GlideApp.with(getApplication())
            .asBitmap()
            .load(MovieDbImagePath.backdrop(movie.backdrop_path()))
            .submit(800, 450);

    return Single.fromFuture(futureTarget)
        .observeOn(executorProvider.ioScheduler())
        .map(bitmap -> Palette.from(bitmap).generate());
  }

  private List<Identifiable> convertToRecyclerViewItems(MovieSwatch movieSwatch) {
    ArrayList<Identifiable> items = Lists.newArrayList(movieSwatch, movieSwatch.movie());

    List<MovieVideo> videos = FluentIterable.from(movieSwatch.movie().videos().results())
        .filter(youTubeFilter).toList();
    if (!videos.isEmpty()) {
      String headerText = getApplication().getString(R.string.details_header_videos, videos.size());
      items.add(HeaderString.create(headerText));
      items.addAll(videos);
    }

    List<MovieReview> reviews = movieSwatch.movie().reviews().results();
    if (!reviews.isEmpty()) {
      int count = reviews.size();
      String headerText = getApplication().getString(R.string.details_header_reviews, count);
      items.add(HeaderString.create(headerText));
      items.addAll(reviews);
    }

    return items;
  }

  public void toggleFavorite() {
    if (this.movie == null) {
      Timber.w("movie == null");
      return;
    }

    Completable.fromAction(() -> {
      MovieEntity entity = movieDao.getMovieById(movie.id());
      if (entity == null) {
        MovieEntity movieEntity = modelConverter.convertToEntity(this.movie);
        movieDao.insertMovie(movieEntity);
      } else {
        movieDao.deleteMovie(entity);
      }
      preferencesManager.setFavoritesDirty(true);
    }).subscribeOn(executorProvider.ioScheduler()).subscribe();
  }

  private LiveData<Boolean> isMovieSaved(int movieId) {
    LiveData<Integer> count = movieDao.isMovieSaved(movieId);
    return Transformations.map(count, c -> c > 0);
  }
}
