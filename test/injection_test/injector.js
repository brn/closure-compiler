/**
 * @fileoverview 依存性の注入を行う。
 * 基本的に依存関係は各クラスのコンストラクタの引数名と、
 * Injector.bindによって結び付けられた値によって解決される。
 * もし、メソッドを別途呼び出して値をセットする場合はInjector.injectにメソッドを指定する。
 * メソッドの依存関係解決も引数名とInjector.bindに設定された名前で行われる。
 * injectorの各メソッドはClosureCompilerによって単純なnew呼び出しに変換される。
 * @author Taketshi Aono
 */


camp.module("camp.dependencies", function(exports) {
  var Config = camp.using('camp.dependencies.Config');
  var asserts = camp.using("goog.asserts");
  var Disposable = camp.using("goog.Disposable");
  var DisposeUtil = camp.using("camp.utils.DisposeUtil");
  var RuntimeException = camp.using("camp.exceptions.RuntimeException");
  var raise = camp.using("camp.exceptions.raise");
  var FunctionUtil = camp.using("camp.utils.FunctionUtil");
  var object = camp.using("goog.object");
  var Interceptor = camp.using('camp.aop.Interceptor');
  var RefrectionUtil = camp.using('camp.utils.RefrectionUtil');
  var Binder = camp.using('camp.dependencies.Binder');
  var Logger = camp.using('goog.debug.Logger');


  /**
   * @param {Config} config
   */
  exports.configure = function (config) {
    config.verify();
    config.setup();
  };

  //Closure Compilerがインライン化するのでinjectorの各メソッドは
  //コンパイル後に消える
  if (!goog.DEBUG) {
    /**
     * コンパイル後にグローバルな型以外はここに集められる。
     * @type {Object}
     */
    exports.injectionRegistry = {};
  } else {
    var log = Logger.getLogger('Injector');
    log.setLevel(Logger.Level.FINE);

    var IGNORE_METHODS = [
          'constructor',
          'toString',
          'valueOf'
        ];


    /**
     * @constructor
     * @param {Binder} binderList
     */
    exports.Injector = function(binderList) {
      this._injections = {};
      this._interceptors = [];
      binderList.forEach(function(binder) {
        var injections = binder.getInjections();
        for (var prop in injections) {
          this._injections[prop] = injections[prop];
        }
        this._interceptors = this._interceptors.concat(binder.getInterceptors());
      }, this);
    };


    /**
     * 依存性を解決した上でインスタンスを生成する。
     *
     * @template T
     * @param {function(new:T,...)} classConstructor
     * @return {T}
     * @example
     *
     * function Bar(foo) {}
     *
     * //この呼出でBarクラスの引数fooにInjector.bindで指定されている値が自動で注入される。
     * Injector.createInstance(Bar);
     * @see {camp.dependencies.Injector.bind}
     */
    exports.Injector.prototype.createInstance = function(classConstructor) {
      return this._doCreate(classConstructor);
    };


    /**
     * メソッドインジェクションが必要な場合に呼び出す。
     *
     * @template T
     * @param {function(new:T,...):void} classConstructor
     * @param {...string} var_args
     * @example
     *
     * function Bar() {}
     *
     * //メソッドインジェクションを指定
     * Injector.inject(Bar, 'setFoo');
     *
     * Bar.prototype._foo = null;
     *
     * Bar.prototype.setFoo = function (foo) {
     *   this._foo = foo;
     * }
     *
     * //この呼出でsetFooがInjector.bindの'foo'プロパティを引数に呼び出される
     * //この呼出は次のように展開される
     * //(function () {
     * //  var instance = new Bar();
     * //  instance.setFoo(new Foo())
     * //  return instance;
     * //})()
     * Injector.createInstance(Bar);
     *
     * @see {camp.dependencies.Injector.createInstance}
     * @see {camp.dependencies.Injector.bind}
     */
    exports.Injector.inject = function(classConstructor, var_args) {
      asserts.assertFunction(classConstructor, "camp.dependencies.injector@inject()");
      var args = Array.prototype.slice.call(arguments);
      classConstructor = args.shift();
      if (!classConstructor._injections) {
        classConstructor._injections = RefrectionUtil.parseArguments(classConstructor);
      } else {
        classConstructor._injections = classConstructor._injections.concat(RefrectionUtil.parseArguments(classConstructor));
      }
      var methodInjections = {};
      args.forEach(function(item) {
        methodInjections[item] = 1;
      });
      classConstructor._methodInjections = methodInjections;
    };


    /**
     * @template T
     * @param {function(new:T,...)} classConstructor
     * @return {T}
     */
    exports.Injector.prototype._doCreate = function(classConstructor) {
      asserts.assertFunction(classConstructor, "camp.dependencies.injector@_doCreate()");
      var injections;
      var args;
      var instance;
      if (classConstructor._provider) {
        if (!camp.isDefined(classConstructor._provider._injections)) {
          classConstructor._provider._injections = RefrectionUtil.parseArguments(classConstructor._provider);
        }
        injections = classConstructor._provider._injections;
        args = this._createArguments(injections);
        instance = classConstructor._provider.apply(null, args);
      } else {
        if (classConstructor.getInstance) {
          instance = classConstructor.getInstance();
        } else {
          if (!camp.isDefined(classConstructor._injections)) {
            classConstructor._injections = RefrectionUtil.parseArguments(classConstructor);
          }
          injections = classConstructor._injections;
          args = this._createArguments(injections);
          instance = this._invokeNewCall(classConstructor, args);
        }
      }
      if (!classConstructor.__done__) {
        if (classConstructor.getInstance) {
          classConstructor.__done__ = true;
        }
        this._parseMethods(classConstructor._methodInjections || [], instance);
      }
      return instance;
    };


    /**
     * 引数の自動生成を行う
     * @private
     * @param {Array.<string>} injections
     * @return {Array}
     */
    exports.Injector.prototype._createArguments = function(injections) {
      var args = [];
      var injection;
      for (var i = 0, len = injections.length; i < len; i++) {
        injection = injections[i];
        if (injection in this._injections) {
          log.fine('inject : ' + injection);
          injection = this._injections[injection];
          if (typeof injection === 'function' && (injection._resolveInjection || RefrectionUtil.isTreatFunctionAsConstructor(injection))) {
            args[i] = this._doCreate(injection);
          } else {
            args[i] = injection;
          }
        } else {
          args[i] = null;
        }
      }
      return args;
    };


    /**
     * メソッドインジェクションの設定を行う
     * @private
     * @param {Object} targets
     * @param {Object} instance
     */
    exports.Injector.prototype._parseMethods = function(targets, instance) {
      var args;
      for (var prop in instance) {
        if (prop in targets && IGNORE_METHODS.indexOf(prop) === -1) {
          if (!instance[prop]._injections) {
            instance[prop]._injections = RefrectionUtil.parseArguments(instance[prop]);
          }
          args = this._createArguments(instance[prop]._injections);
          instance[prop].apply(instance, args);
        }
      }
    };


    /**
     * インスタンス生成
     * @template T
     * @param {function(new:T,...)} classConstructor
     * @param {Array} injections
     * @return {T}
     */
    exports.Injector.prototype._invokeNewCall = function(classConstructor, injections) {
      if (classConstructor._resolveInjection) {
        this._applyInterceptor(classConstructor._constructor);
        return classConstructor.apply(null, injections);
      } else {
        this._applyInterceptor(classConstructor);
        var instance;
        /**
         * @constructor
         */
        function NewCallProxyClass() {}
        NewCallProxyClass.prototype = classConstructor.prototype;
        instance = new NewCallProxyClass;
        classConstructor.apply(instance, injections);
        return instance;
      }
    };


    exports.Injector.prototype._applyInterceptor = function(classConstructor) {
      if (this._interceptors.length > 0) {
        this._interceptors.forEach(function(args) {
          if (args[0].match(classConstructor)) {
            Interceptor.applyInterceptor(classConstructor, args[1].match(classConstructor), args[2], args[3]);
          }
        });
      }
    };
  }
});
