var goog = {
      mixin : function(a,b) {
        for (var prop in b) {
          a[prop] = b[prop];
        }
      }
    };
camp.module("test.module", ['bindings', 'bindings2'], function (exports) {

  /**
   * @returns {{item : string, item2 : string}}
   */
  exports.bindings.items = function() {
    return {
      item : 'item',
      item2 : 'item2'
    };
  };

  /**
   * @returns {{item3 : string}}
   */
  exports.bindings2.items2 = function(bindings) {
    return {
      item3 : 'item3'
    };
  };


  var x = {};
  var m = exports.bindings.items();
  var n = exports.bindings2.items2();
  for (var prop in m) {
    x[prop] = m[prop];
  }
  for (prop in n) {
    x[prop] = n[prop];
  }

  document.getElementById('aaaaa').innerHTML = x.item + x.item2 + x.item3;
});