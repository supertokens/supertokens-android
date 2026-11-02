class FakeTask {
    String name = 'testDebugUnitTest'
    String path = ':app:testDebugUnitTest'
    def before
    def after
    def suite
    void beforeTest(Closure action) { before = action }
    void afterTest(Closure action) { after = action }
    void afterSuite(Closure action) { suite = action }
}

class FakeTasks {
    def task
    def withType(type) { this }
    void all(Closure action) { action(task) }
}

class FakeGradle {
    void projectsEvaluated(Closure action) { action() }
}

def task = new FakeTask()
def binding = new Binding([
    gradle: new FakeGradle(), Test: Object,
    tasks: new FakeTasks(task: task), allprojects: { Closure action -> action() }
])
new GroovyShell(binding).evaluate(new File(args[0]))
def first = [className: 'com.example.RetrofitTest', name: 'login']
def second = [className: 'com.example.OkHttpTest', name: 'refresh']
if (args.size() > 1 && args[1] == 'many') {
    5001.times {
        def item = [className: 'com.example.OkHttpTest', name: 'iteration']
        task.before(item)
        task.after(item, [resultType: 'SUCCESS'])
    }
    task.before(second)
    Runtime.runtime.halt(43)
}
if (args.size() > 1 && args[1] == 'unwritable') {
    task.before(first)
    task.after(first, [resultType: 'FAILURE'])
    System.exit(23)
}
if (args.size() > 1 && args[1] == 'unwritable-after-setup') {
    def directory = new File(System.getenv('ST_TEST_REPORT_DIR'), 'progress')
    directory.delete()
    directory.text = 'not a directory'
    task.before(first)
    task.after(first, [resultType: 'FAILURE'])
    System.exit(23)
}
task.before(first)
task.after(first, [resultType: 'FAILURE'])
def parameter = [className: 'com.example.RetrofitTest', name: 'login[SECRETtoken]']
task.before(parameter)
task.after(parameter, [resultType: 'SUCCESS'])
task.before(second)
Runtime.runtime.halt(43)
