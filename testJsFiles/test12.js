camp.module("camp.test.module", ['deps'], function (exports) {
  var binder = camp.using('camp.dependencies.binder');
  var newWith = camp.using('camp.dependencies.newWith');

  /**
   * @constructor
   * @param {{test:Object, test2:string}} a
   */
  function Test(a) {

  }

  /**
   * @constructor
   */
  function PubSub() {

  };

  /**
   * @constructor
   */
  function DataSourceManager(pubsub) {
    this._node = document.getElementById('aaa');
  }

  /**
   * @param {SDKListViewModel} inst
   */
  DataSourceManager.prototype.registerDataSource = function(inst) {
    this._node.innerHTML = inst.getMessage();
  };

  /**
   * @constructor
   * @param {Manager} manager
   */
  function Service(manager) {
    this._message = 'aaa' + manager.getMessage();
  }

  /**
   * messageの取得
   * @return {string} message
   */
  Service.prototype.getMessage = function() {
    return this._message;
  };


  /**
   * @constructor
   */
  function Manager() {
    this._message = '';
  }

  /**
   * messageの取得
   * @return {string} message
   */
  Manager.prototype.getMessage = function() {
    return this._message;
  };


  /**
   * @constructor
   * @param {Service} service
   */
  function SDKListViewModel(service) {
    this._service = service;
  }

  /**
   * @returns {string}
   */
  SDKListViewModel.prototype.getMessage = function() {
    return 'ok' + this._service.getMessage();
  };

  /**
   * @return {{pubsub : PubSub}}
   */
  exports.deps.dataSourceManager = function() {
    return {
      pubsub : new PubSub
    };
  };


  /**
   * @return {{service : Service}}
   */
  exports.deps.sdkListViewModel = function() {
    return {
      service : newWith(Service, exports.deps.service)
    };
  };

  /**
   * @return {{manager : Manager}}
   */
  exports.deps.service = function() {
    return {
      manager : new Manager
    };
  };


  var injector = binder.bind(exports.deps, {
        dataSourceManager : DataSourceManager
      });

  
  var dataSourceManager = injector.dataSourceManager();
  dataSourceManager.registerDataSource(newWith(SDKListViewModel, exports.deps.sdkListViewModel));
  window['dataSourceManager'] = dataSourceManager;
});