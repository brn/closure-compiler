/**
 * @namespace camp.vm
 */
camp.module('camp.vm', function (exports) {
  var Injector = camp.using('camp.injector.Injector');
  /**
   * @constructor
   */
  exports.DataSource = function (name) {
    this._dataSourceManager = null;
    this._listeners = {};
    this._name = name;
  }


  exports.DataSource.prototype.getName = function () {
    return this._name;
  }

  /**
   * observableの更新を行う
   * @param {!string} name 対象のプロパティ名
   * @param {*} val 更新する値
   * @param {!boolean=} opt_isforce 強制的に更新するかどうか
   */
  exports.DataSource.prototype.set = function (name, val, opt_isforce) {
    if (opt_isforce) {
      this[name](null);
    }
    this[name](val);
  }

  /**
   * observableの値の取得
   * @param {!string} name
   * @returns {*}
   */
  exports.DataSource.prototype.get = function (name) {
    return this[name];
  }


  /**
   * ko.obserableをclosure compiler
   * にminifyさせないためのユーティリティ
   * @param {!string} name
   * @param {*=} value
   */
  exports.DataSource.prototype.observe = function (name, opt_val) {
    this[name] = ko.observable(opt_val);
  }

  /**
   * ko.obserableArrayをclosure compiler
   * にminifyさせないためのユーティリティ
   * @param {!string} name
   * @param {*=} value
   */
  exports.DataSource.prototype.observeArray = function (name, opt_val) {
    this[name] = ko.observableArray(opt_val);
  }

  exports.DataSource.prototype.command = function (name) {
    this[name] = ko.observable(new Command);
  }

  exports.DataSource.prototype.trigger = function (name, opt_args) {
    var args = Array.prototype.slice.call(arguments);
    name = args.shift();
    var command = this[name]();
    command.reset(args);
    command.setPhase('update');
    this.forceUpdate(name);
  };

  exports.DataSource.prototype.setDataSourceManager = function (dataSourceManager) {
    this._dataSourceManager = dataSourceManager;
  }

  exports.DataSource.prototype.listen = function (registeredName, fn) {
    this._listeners[registeredName] = {fn : fn, context : this};
  }

  exports.DataSource.prototype.getListener = function (name) {
    return this._listeners[name];
  }

  exports.DataSource.prototype.notify = function (var_args) {
    var args = Array.prototype.slice.call(arguments);
    return this._dataSourceManager.notify(this._name, args);
  }
});
