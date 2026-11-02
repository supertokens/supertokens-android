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
task.before(first)
task.after(first, [resultType: 'FAILURE'])
task.before(second)
Runtime.runtime.halt(43)
