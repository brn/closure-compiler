var local = {};
camp.module("camp.foo.bar.baz", function (exports) {
  /**
   * @constructor
   */
  local.Hoge = function Hoge() {

  };

  local.Hoge.prototype.pyon = function() {
    document.getElementById('div').innerHTML = this;
  };



  /**
   * @constructor
   */
  exports.Foo = function() {
    this._hoge = new local.Hoge;
  };


  exports.main = function() {
    new exports.Foo().pyon();
  };
});
