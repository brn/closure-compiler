camp.module("camp.injections", function (exports) {
  if (goog.DEBUG) {
    exports.Binder = function() {
      this._injections = {};
      this._interceptors = [];
    };

    /**
     * injectionsの取得
     * @return {Object} injections
     */
    exports.Binder.prototype.getInjections = function() {
      return this._injections;
    };


    /**
     * interceptorsの取得
     * @return {Array} interceptors
     */
    exports.Binder.prototype.getInterceptors = function() {
      return this._interceptors;
    };


    /**
     * 値と名前を結びつける。
     *
     * @param {string} name
     * @param {*} value
     * @example
     *
     * function Bar(foo) {this._foo = foo;}
     *
     * Injector.bind('foo', Foo);
     *
     * //この時点で new Bar(new Foo)と同等のインスタンス生成が行われる
     * Injector.createInstance(Bar);
     */
    exports.Binder.prototype.bind = function(name, value) {
      this._injections[name] = value;
    };


    exports.Binder.prototype.bindInterceptor = function(classMatcher, methodMatcher, jointPoint, interceptor) {
      this._interceptors.push([classMatcher, methodMatcher, jointPoint, interceptor]);
    };


    /**
     * Providerを定義する。
     * Providerを定義することで、インスタンスの生成方法を指定することができる。
     * camp.injections.injectorに未対応なクラス(goog名前空間のクラス等)
     * に対してインスタンス生成方法を与えることが可能。
     *
     * @template T
     * @param {?string} name
     * @param {function(new:T,...)} classConstructor
     * @param {function():T} provider
     * @example
     *
     * function Foo(bar) {this._bar = bar;}
     *
     * binder.bindProvider(Foo, function () {
     *   //Injector.get経由で呼び出すことで依存性解決が行われる
     *   return new Foo(Injector.get('bar'));
     * })
     *
     * //この呼出で、binder.bindProviderの第二引数に渡したProvider関数が呼ばれる
     * Injector.createInstance(Foo);
     * @see {camp.injections.Injector.get}
     * @see {camp.injections.Injector.createInstance}
     * @see {camp.injections.Injector.bind}
     */
    exports.Binder.prototype.bindProvider = function(name, classConstructor, provider) {
      if (name) {
        provider._resolveInjection = true;
        provider._constructor = classConstructor;
        this._injections[String(name)] = provider;
      }
      classConstructor._provider = function() {
        return provider.apply(null, arguments);
      };
    };
  }
});