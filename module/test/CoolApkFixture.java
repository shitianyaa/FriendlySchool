import java.util.ArrayList;
import java.util.List;

/** 模拟列表处理时先登记广告、随后异步插入的调用方。 */
public final class CoolApkFixture {
    /** Android 桌面 stub 的构造器会抛异常，仅在测试中绕过构造器。 */
    public static SharedActivity activity(Class<? extends SharedActivity> type) throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (SharedActivity) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }

    public static class SharedActivity extends android.app.Activity {
        public int creates;
        public int finishes;
        public boolean finishedAfterCreate;
        public IllegalStateException failure;

        @Override
        protected void onCreate(android.os.Bundle state) {
            creates++;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void finish() {
            finishes++;
            finishedAfterCreate = creates > 0;
        }
    }

    public static class FirstAdActivity extends SharedActivity {}
    public static class SecondAdActivity extends SharedActivity {}
    public static class NormalActivity extends SharedActivity {}

    public int sponsorRegistrations;
    public int calls;
    public boolean addSponsor;
    public boolean fail;

    public List<Object> process(List<Object> input, boolean ignored) {
        calls++;
        for (Object item : input) {
            if (item instanceof Entity && ((Entity) item).getEntityTemplate().contains("sponsor")) {
                sponsorRegistrations++;
            }
        }
        if (fail) {
            throw new IllegalStateException("original failure");
        }
        if (addSponsor) {
            List<Object> output = new ArrayList<Object>(input);
            output.add(new Entity("sponsorCard"));
            return output;
        }
        return input;
    }

    public String process(String input) {
        return input;
    }

    /** 原方法不遍历列表，测试可单独计数 Hook 的摘要读取。 */
    public List<?> observe(List<?> input) {
        calls++;
        if (fail) {
            throw new IllegalStateException("observation original failure");
        }
        return input;
    }

    public static class CountingList extends java.util.AbstractList<Object> {
        public int reads;

        @Override
        public int size() {
            return 1000;
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException();
            }
            reads++;
            return null;
        }
    }

    public static class Entity {
        private final String template;

        public Entity(String template) {
            this.template = template;
        }

        public String getEntityTemplate() {
            return template;
        }

        public String getEntityId() {
            return "normal-id";
        }
    }
}
