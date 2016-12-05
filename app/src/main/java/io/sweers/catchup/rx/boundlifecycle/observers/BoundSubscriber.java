package io.sweers.catchup.rx.boundlifecycle.observers;

import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.internal.disposables.DisposableHelper;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.plugins.RxJavaPlugins;
import io.sweers.catchup.rx.boundlifecycle.LifecycleProvider;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rx.exceptions.OnErrorNotImplementedException;

import static io.sweers.catchup.rx.boundlifecycle.observers.Util.DEFAULT_ERROR_CONSUMER;
import static io.sweers.catchup.rx.boundlifecycle.observers.Util.EMPTY_ACTION;
import static io.sweers.catchup.rx.boundlifecycle.observers.Util.createTaggedError;
import static io.sweers.catchup.rx.boundlifecycle.observers.Util.emptyActionIfNull;
import static io.sweers.catchup.rx.boundlifecycle.observers.Util.emptyConsumerIfNull;
import static io.sweers.catchup.rx.boundlifecycle.observers.Util.emptyErrorConsumerIfNull;

public final class BoundSubscriber<T> implements Subscriber<T>, Disposable {

  private final AtomicReference<Subscription> mainSubscription = new AtomicReference<>();
  private final AtomicReference<Disposable> lifecycleDisposable = new AtomicReference<>();
  private final Maybe<?> lifecycle;
  private final Consumer<? super T> consumer;
  private final Consumer<? super Throwable> errorConsumer;
  private final Action completeAction;

  private BoundSubscriber(Maybe<?> lifecycle,
      Consumer<? super Throwable> errorConsumer,
      Consumer<? super T> consumer,
      Action completeAction) {
    this.lifecycle = lifecycle;
    this.errorConsumer = emptyErrorConsumerIfNull(errorConsumer);
    this.consumer = emptyConsumerIfNull(consumer);
    this.completeAction = emptyActionIfNull(completeAction);
  }

  @Override
  public final void onSubscribe(Subscription s) {
    if (SubscriptionHelper.setOnce(mainSubscription, s)) {
      DisposableHelper.setOnce(lifecycleDisposable, lifecycle.subscribe(e -> dispose()));
      onStart();
    }
  }

  /**
   * Called once the single upstream Subscription is set via onSubscribe.
   */
  protected void onStart() {
    mainSubscription.get()
        .request(Long.MAX_VALUE);
  }

  /**
   * Requests the specified amount from the upstream if its Subscription is set via
   * onSubscribe already.
   * <p>Note that calling this method before a Subscription is set via onSubscribe
   * leads to NullPointerException and meant to be called from inside onStart or
   * onNext.
   *
   * @param n the request amount, positive
   */
  protected final void request(long n) {
    mainSubscription.get()
        .request(n);
  }

  /**
   * Cancels the Subscription set via onSubscribe or makes sure a
   * Subscription set asynchronously (later) is cancelled immediately.
   * <p>This method is thread-safe and can be exposed as a public API.
   */
  protected final void cancel() {
    dispose();
  }

  @Override
  public final boolean isDisposed() {
    return mainSubscription.get() == SubscriptionHelper.CANCELLED;
  }

  @Override
  public final void dispose() {
    synchronized (this) {
      DisposableHelper.dispose(lifecycleDisposable);
      SubscriptionHelper.cancel(mainSubscription);
    }
  }

  @Override
  public final void onNext(T value) {
    if (consumer != null) {
      try {
        consumer.accept(value);
      } catch (Exception e) {
        Exceptions.throwIfFatal(e);
        onError(e);
      }
    }
  }

  @Override
  public void onError(Throwable e) {
    if (errorConsumer != null) {
      try {
        errorConsumer.accept(e);
      } catch (Exception e1) {
        Exceptions.throwIfFatal(e1);
        RxJavaPlugins.onError(new CompositeException(e, e1));
      }
    } else {
      throw new OnErrorNotImplementedException(e);
    }
  }

  @Override
  public final void onComplete() {
    if (completeAction != null) {
      try {
        completeAction.run();
      } catch (Exception e) {
        Exceptions.throwIfFatal(e);
        RxJavaPlugins.onError(e);
      }
    }
  }

  public static class Creator<T> extends BaseObserver.BaseCreator<Creator<T>> {

    private Consumer<? super T> nextConsumer;
    private Action completeAction;

    Creator(LifecycleProvider<?> provider) {
      super(provider);
    }

    Creator(Observable<?> lifecycle) {
      super(lifecycle);
    }

    Creator(Maybe<?> lifecycle) {
      super(lifecycle);
    }

    public Creator<T> onNext(Consumer<? super T> nextConsumer) {
      this.nextConsumer = nextConsumer;
      return this;
    }

    public Creator<T> onComplete(Action completeAction) {
      this.completeAction = completeAction;
      return this;
    }

    public Subscriber<T> asConsumer(Consumer<? super T> nextConsumer) {
      return new BoundSubscriber<>(lifecycle, DEFAULT_ERROR_CONSUMER, nextConsumer, EMPTY_ACTION);
    }

    public Subscriber<T> asConsumer(String errorTag, Consumer<? super T> nextConsumer) {
      return new BoundSubscriber<>(lifecycle,
          createTaggedError(errorTag),
          nextConsumer,
          EMPTY_ACTION);
    }

    public Subscriber<T> around(Subscriber<T> subscriber) {
      return new BoundSubscriber<>(lifecycle,
          subscriber::onError,
          subscriber::onNext,
          subscriber::onComplete);
    }

    public Subscriber<T> create() {
      return new BoundSubscriber<>(lifecycle, errorConsumer, nextConsumer, completeAction);
    }
  }
}