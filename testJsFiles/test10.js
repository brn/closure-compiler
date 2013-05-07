/**
 * @constructor
 * @param {string} a
 * @param {string} b
 * @param {string} c
 */
function TestClass(a,b,c) {
  this.a = a;
  this.b = b;
  this.c = c;
}

TestClass.prototype.toString = function() {
  return this.a + this.b + this.c;
};

function mixin(a,b) {
  for (var prop in b) {
    a[prop] = b[prop];
  }
}

/**
 * @template T
 * @param {T} values
 * @returns {TestClass}
 */
TestClass.newInstance = function(values) {
  return new TestClass(values.getA(), values.getB(), values.getC());
};


/**
 * @this {Object}
 */
var valuesTrait = function() {
      /**
       * @return {string}
       */
      this.getA = function() {
        return this.a_;
      };

      /**
       * @return {string}
       */
      this.getB = function() {
        return this.b_;
      };

      /**
       * @return {string}
       */
      this.getC = function() {
        return this.c_;
      };
    };

/**
 * @constructor
 */
function Values2() {
  /**
   * @private {string}
   */
  this.a_ = 'a';

  /**
   * @private {string}
   */
  this.b_ = 'b';

  /**
   * @private {string}
   */
  this.c_ = 'c';
  valuesTrait.call(this);
}



document.getElementById('a').innerHTML = TestClass.newInstance(new Values2).toString();