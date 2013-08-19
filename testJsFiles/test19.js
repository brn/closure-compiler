camp.module("foo.bar.baz", function (exports) {

  /**
   * @constructor
   */
  function Bar1() {

  }

  /**
   * @returns {string}
   */
  Bar1.prototype.foo = function() {
    return '100';
  };


  /**
   * @constructor
   */
  function Bar2() {
    this._bar1 = new Bar1();
  }

  /**
   * @returns {string}
   */
  Bar2.prototype.bar = function() {
    return '200';
  };


  /**
   * @returns {string}
   */
  Bar2.prototype.foo = function() {
    return this._bar1.foo();
  };


  /**
   * @constructor
   */
  function Bar3() {
    this._bar1 = new Bar1();
  }

  /**
   * @returns {string}
   */
  Bar3.prototype.foo = function() {
    return this._bar1.foo();
  };


  var i = new Bar2();
  var i2 = new Bar3();
  document.getElementById('aaaa').innerHTML = i.bar() + i.foo() + i2.foo();

});