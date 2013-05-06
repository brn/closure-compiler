var modules = {
      init : function(modules, fn) {
        var bindings = {};
        for (var i = 0, len = modules.length; i < len; i++) {
          modules[i].configure(bindings);
        }
        fn(bindings);
      }
    };

function DataSourceManager() {
  this._items = [];
}

DataSourceManager.prototype.registerDataSource = function(item) {
  this._items.push(item);
};

/**
 * @constructor
 */
function Module() {

}

Module.prototype.configure = function(bindings) {
  bindings.dataSourceManager = function() {
    return new DataSourceManager();
  };
};

modules.init([new Module()], function(bindings) {
  var dataSourceManager = bindings.dataSourceManager();
});