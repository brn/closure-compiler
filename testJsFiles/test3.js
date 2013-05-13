/**
 * @define {boolean} Overridden to true by the compiler when --closure_pass
 *                   or --mark_as_compiled is specified.
 */
var COMPILED = false;

camp.module(
  'camp.vm.interaction',
  ['Service',
   'Test',
   'Test2',
   'Fuga',
   'Hoge',
   'Test3',
   'Test4',
   'DataSourceManager',
   'Base1',
   'Base2',
   'Base3',
   'DefaultModule',
   'Module',
   'CalendarCacheManager'],
  function (exports) {
    var Disposable = camp.using('camp.dependencies.Disposable');
    var injector = camp.using('camp.dependencies.newInstanceor');

    /**
     * @constructor
     * @extends {Disposable}
     */
    exports.CalendarCacheManager = function(calendarCacheSize, serviceProvider) {
      goog.base(this);

      /**
       * @type {Array.<string>}
       */
      this._keyList = [];

      /**
       * @type {Object}
       */
      this._caches = {};
    };
    goog.inherits(exports.CalendarCacheManager, Disposable);

    /**
     * @constructor
     */
    function Service() {}
    goog.addSingletonGetter(Service);
    injector.declInjections(Service, 'setNode(node)');
    Service.prototype._node = null;

    /**
     * @returns {Element}
     */
    Service.prototype.getNode = function () {
      return this._node;
    };

    Service.prototype.setNode = function (node) {
      //goog.base(this, 1, 2, 3);
      this._node = node;
    };

    /**
     * @type {function(new:Service)}
     */
    exports.Service = Service;

    /**
     * @constructor
     */
    function Service2 () {
      this._node = document.getElementById('id');
    }

    /**
     * @returns {Element}
     */
    Service2.prototype.getNode = function () {
      return this._node;
    };


    /**
     * @constructor
     * @param {string} name1
     * @param {camp.vm.interaction.Test2} test2
     */
    exports.Test = function (name1, test2) {
      this._x = name1;
      this._test2 = test2;
    };
    injector.declInjections(exports.Test, 'setService(service)');


    exports.Test.prototype = {
      /**
       * @returns {string}
       */
      getName : function () {
        return this._x + this._test2.getName() + this._service.getNode().innerHTML;
      },


      /**
       * @param {camp.vm.interaction.Service} service
       */
      setService : function (service) {
        this._service = service;
      },

      /**
       * @private {camp.vm.interaction.Service}
       */
      _service : null
    };



    /**
     * @constructor
     * @param {string} name2
     */
    exports.Test2 = function (name2) {
      this._name = name2;
    };


    /**
     * @returns {string}
     */
    exports.Test2.prototype.getName = function () {
      return this._name;
    };

    /**
     * @constructor
     */
    function Fuga() {}

    /**
     * @constructor
     * @extends {Fuga}
     */
    function Hoge() {};
    goog.inherits(Hoge, Fuga);

    exports.Fuga = Fuga;
    exports.Hoge = Hoge;

    /**
     * @constructor
     * @extends {camp.vm.interaction.Test}
     */
    exports.Test3 = function (name1, test2) {
      exports.Test.call(this, name1, test2);
    };
    goog.inherits(exports.Test3, exports.Test);


    /**
     * @constructor
     * @param {string} name1
     * @param {string} name2
     */
    exports.Test4 = function (name1, name2) {
      this.a = name1;
      this.b = name2;
    };

    exports.Test4.prototype.c = '';


    /**
     * @param {camp.vm.interaction.Test2} c
     */
    exports.Test4.prototype.setC = function (c) {
      this.c = c;
    };

    exports.Test4.prototype.get = function () {
      return this.a + this.b + this.c.getName();
    };

    /**
     * @constructor
     */
    function PubSub(){
      this._node = document.getElementsById('echo');
    }

    /**
     * @return {Element} node
     */
    PubSub.prototype.getNode = function() {
      return this._node;
    };


    /**
     * DataSourceをまとめて公開するためのユーティリティ
     * @constructor
     * @param {PubSub} pubsub
     */
    exports.DataSourceManager = function(pubsub, test4) {
      /**
       * @private {!Object}
       */
      this._dataSources = {};

      /**
       * @private {PubSub}
       */
      this._pubsub = pubsub;

      this._test4 = test4;
    };

    /**
     * @param {string} value
     */
    exports.DataSourceManager.prototype.echo = function(value) {
      this._pubsub.getNode().innertHTML = value;
    };



    exports.Test.prototype.setService = function() {
      return function(service) {
        this._service = service;
      };
    }();
    injector.declInjections(exports.Test3, "setService(service)");

    /**
     * @constructor
     */
    exports.Base1 = function() {

    };

    exports.Base1.prototype.setName = function(name1) {
      this.name1 = name1;
    };

    /**
     * @constructor
     * @extends {exports.Base1}
     */
    exports.Base2 = function() {

    };


    /**
     * @constructor
     * @extends {exports.Base2}
     */
    exports.Base3 = function() {

    };
    injector.declInjections(exports.Base3, "setName(name1)");

    exports.Base3.prototype.insert = function() {
      document.getElementById('test').innertHTML = "test";
    };

    /**
     * @constructor
     */
    exports.DefaultModule = function() {};

    /**
     * calendarCacheManagerの取得
     * @return {exports.CalendarCacheManager} calendarCacheManager
     */
    exports.DefaultModule.prototype.getCalendarCacheManager = function() {
      return injector.getInstance(exports.CalendarCacheManager, this);
    };

    /**
     * calendarCacheSizeの取得
     * @return {number} calendarCacheSize
     */
    exports.DefaultModule.prototype.getCalendarCacheSize = function() {
      return 20;
    };


    /**
     * dataSourceManagerの取得
     * @return {exports.DataSourceManager} dataSourceManager
     */
    exports.DefaultModule.prototype.getDataSourceManager = function() {
      return injector.newInstance(exports.DataSourceManager, this);
    };

    /**
     * pubSubの取得
     * @return {PubSub} pubSub
     */
    exports.DefaultModule.prototype.getPubsub = function() {
      return new PubSub;
    };

    /**
     * serviceの取得
     * @return {exports.Service} service
     */
    exports.DefaultModule.prototype.getService = function() {
      return injector.getInstance(exports.Service, this);
    };

    /**
     * testの取得
     * @return {exports.Test} test
     */
    exports.DefaultModule.prototype.getTest = function() {
      return injector.getInstance(exports.Test, this);
    };

    /**
     * test2の取得
     * @return {exports.Test2} test2
     */
    exports.DefaultModule.prototype.getTest2 = function() {
      return injector.newInstance(exports.Test2, this);
    };


    /**
     * test4の取得
     * @return {exports.Test4} test4
     */
    exports.DefaultModule.prototype.getTest4 = function() {
      var ret = new exports.Test4(this.getName1(), this.getName2());
      ret.setC(this.getTest2());
      return ret;
    };

    /**
     * @constructor
     * @extends {exports.DefaultModule}
     */
    exports.Module = function() {
      goog.base(this);
    };
    goog.inherits(exports.DefaultModule);

    /**
     * name1の取得
     * @return {string} name1
     */
    exports.Module.prototype.getName1 = function() {
      return 'name1';
    };

    /**
     * name2の取得
     * @return {string} name2
     */
    exports.Module.prototype.getName2 = function() {
      return 'name2';
    };
  });
