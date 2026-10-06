package com.yiran.friendlyschool.core;

import com.yiran.friendlyschool.targets.CoolApkTarget;
import com.yiran.friendlyschool.targets.GybkTarget;
import com.yiran.friendlyschool.targets.JMComicTarget;
import com.yiran.friendlyschool.targets.WakeUpTarget;
import com.yiran.friendlyschool.targets.YiCampusTarget;

/**
 * 目标注册表 —— 本模块唯一的“往后加东西”的地方。
 */
public final class Targets {

    /** 新增目标：在这里加一行即可。 */
    private static final SchoolTarget[] ALL = new SchoolTarget[] {
            new YiCampusTarget(),
            new WakeUpTarget(),
            new JMComicTarget(),
            new GybkTarget(),
            new CoolApkTarget(),
    };

    private Targets() {
    }

    /** 按包名找目标；不是我们的目标就返回 null（该进程什么都不做）。 */
    public static SchoolTarget forPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (SchoolTarget t : ALL) {
            if (packageName.equals(t.packageName())) {
                return t;
            }
        }
        return null;
    }

    /** 所有目标包名，供 README / 静态作用域声明核对。 */
    public static String[] packages() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            out[i] = ALL[i].packageName();
        }
        return out;
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ALL.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ALL[i].shortName()).append('(').append(ALL[i].packageName()).append(')');
        }
        return sb.toString();
    }
}
