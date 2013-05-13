camp.module("test.module", function (exports) {
  var injector = camp.using('camp.injections.injector');

  /**
   * @constructor
   */
  function Module() {};

  binder.bind(Module, 'dataSourceManager').to(DataSourceManager);
  binder.bind(Module, 'calendarCacheManager').toProvider(function(calendarCacheSize) {
    return new exports.CalendarCacheManager(calendarCacheSize);
  });


});