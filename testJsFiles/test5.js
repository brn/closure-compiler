var goog = {
      provide : function(name) {
        document.write(name);
      }
    };
goog.provide("goog.hoge.hoge.Hoge");

/**
 * @constructor
 */
function Hoge() {
  this.node = document.getElementById('id');
}

Hoge.prototype.setValue = function(value) {
  this.node.innerHTML = value;
};

goog.hoge.hoge.Hoge = Hoge;

(function() {
  var item = new goog.hoge.hoge.Hoge();
  item.setValue('hoge');
})();