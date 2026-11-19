package cordis.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 对应 packages/core/src/utils.ts 的 DisposableList&lt;T&gt;。
 *
 * “一个有序列表，插入时返回可用于移除该条目的 Disposable”：
 * - {@link #push} 追加到尾部；
 * - {@link #unshift} 插入到头部（遍历时最先出现，销毁时最后执行，
 *   对应 EventsService 中 prepend 选项的语义）；
 * - 遍历顺序 = 头部条目（按插入的逆序）+ 尾部条目（按插入顺序）。
 */
public class DisposableList<T> implements Iterable<T> {
    private final List<T> head = new ArrayList<>();
    private final List<T> tail = new ArrayList<>();

    public int size() {
        return head.size() + tail.size();
    }

    /** 追加到尾部，返回用于移除该条目的 Disposable */
    public Disposable push(T value) {
        tail.add(value);
        return () -> removeByIdentity(tail, value);
    }

    /** 插入到头部（最先被遍历，最后被执行），返回用于移除该条目的 Disposable */
    public Disposable unshift(T value) {
        head.add(value);
        return () -> removeByIdentity(head, value);
    }

    /** 按引用（而非 equals）移除条目，对应 TS 中以 sn 为主键的 delete */
    public boolean delete(T value) {
        return removeByIdentity(head, value) || removeByIdentity(tail, value);
    }

    /** 清空并按遍历顺序返回所有条目，对应 clear() */
    public List<T> clear() {
        List<T> values = snapshot();
        head.clear();
        tail.clear();
        return values;
    }

    /** 当前遍历顺序的快照（头部逆序 + 尾部顺序） */
    public List<T> snapshot() {
        List<T> values = new ArrayList<>(size());
        for (int i = head.size() - 1; i >= 0; i--) values.add(head.get(i));
        values.addAll(tail);
        return values;
    }

    @Override
    public Iterator<T> iterator() {
        return snapshot().iterator();
    }

    private static <T> boolean removeByIdentity(List<T> list, T value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == value) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }
}
