camp.module("a.b.c", function (exports) {
  /**
   * @define {boolean} Overridden to true by the compiler when --closure_pass
   *                   or --mark_as_compiled is specified.
   */
  var COMPILED = false;

  if (COMPILED){
    /**
     * @constructor
     */
    exports.A = function(){this.name = 'aaa';document.getElementById('aaa').innerHTML = this.name;};
  } else {
    /**
     * @constructor
     */
    exports.A = function(){this.name = 'aaa';document.getElementById('aaa').innerHTML = this.name;};
  }

  new exports.A();
});