package hamsterwheel.core;

import hamsterwheel.config.Config;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.JFrame;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MouseLocator extends Thread implements MouseListener {

    private Config config;
    private Consumer<MouseUpdate> positionConsumer;
    private Thread pollingRateMeasurerThread;

    private MouseUpdate mouseUpdate = null;
    public List<Integer> buttonsPressed = new ArrayList<>();
    private int mouseUpdateCounter = 0, currentPollingRate = 0;
    private boolean paused = false;

    private long lastRightClickPressed, lastLeftClickPressed, lastLeftClickReleased;
    private float lastClickDuration, lastClickInterval;

    public MouseLocator(Consumer<MouseUpdate> positionConsumer, Config config) {
        this.positionConsumer = positionConsumer;
        this.config = config;
    }

    // --- 1. JNA 底层 API 映射 (为了不依赖复杂的额外包，这里直接映射) ---
    public interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean RegisterRawInputDevices(Memory pRawInputDevices, int uiNumDevices, int cbSize);
        int GetRawInputData(LPARAM hRawInput, int uiCommand, Pointer pData, IntByReference pcbSize, int cbSizeHeader);
        
        // 用于替换窗口消息处理函数的 API (兼容 32/64 位)
        Pointer SetWindowLongPtr(HWND hWnd, int nIndex, WindowProc wndProc);
        Pointer SetWindowLong(HWND hWnd, int nIndex, WindowProc wndProc);
        LRESULT CallWindowProc(Pointer lpPrevWndFunc, HWND hWnd, int Msg, WPARAM wParam, LPARAM lParam);
    }

    public interface WindowProc extends Callback {
        LRESULT callback(HWND hwnd, int uMsg, WPARAM wParam, LPARAM lParam);
    }

    // --- 常量定义 ---
    private static final int WM_INPUT = 0x00FF;
    private static final int RID_INPUT = 0x10000003;
    private static final int RIM_TYPEMOUSE = 0;
    private static final int GWLP_WNDPROC = -4;
    private static final int HEADER_SIZE = 8 + (2 * Native.POINTER_SIZE); // 兼容32/64位头部大小

    // 存储转换后的绝对坐标 (必须加上 volatile 保证多线程可见性)
    private volatile double currentAbsX = 0;
    private volatile double currentAbsY = 0;

    // 新增屏幕宽高成员变量
    private int screenWidth;
    private int screenHeight;

    // --- 类的成员变量 ---
    private JFrame targetFrame;
    private Pointer oldWndProc;
    private WindowProc customWndProc;
    
    // ⭐ 核心：这是连接底层回调和 Java 线程的桥梁
    private ConcurrentLinkedQueue<Point> mouseEventQueue = new ConcurrentLinkedQueue<>();

    /**
     * 绑定目标框架函数
     * @param targetFrame 你的主窗口 (确保传入前已经调用过 targetFrame.setVisible(true))
     */
    public void bindTargetFrame(JFrame targetFrame) {
        this.targetFrame = targetFrame;
    }

    /**
     * 核心初始化逻辑：注册原始输入并拦截 JFrame 消息
     */
    private void initNativeHook() {
        // 1. 获取 JFrame 的底层 Windows 句柄 (HWND)
        HWND hwnd = new HWND(Native.getComponentPointer(targetFrame));

        // 2. 注册 Raw Input (请求主板把鼠标数据发给这个 HWND)
        int ridSize = 4 + 4 + Native.POINTER_SIZE;
        Memory rid = new Memory(ridSize);
        rid.clear();
        rid.setShort(0, (short) 0x01); // UsagePage: Generic Desktop Controls
        rid.setShort(2, (short) 0x02); // Usage: Mouse
        rid.setInt(4, 0x00000100);     // RIDEV_INPUTSINK (即使失去焦点也能接收数据)
        rid.setPointer(8, hwnd.getPointer());

        boolean success = User32Ext.INSTANCE.RegisterRawInputDevices(rid, 1, ridSize);
        if (!success) {
            System.err.println("注册 Raw Input 失败！请检查权限或环境。");
            return;
        }

        // 3. 预先分配好内存，避免在 8000Hz 频率下产生 GC
        final Memory rawInputBuffer = new Memory(64);
        final IntByReference pcbSize = new IntByReference();

        // 获取一次当前系统鼠标的真实位置作为起点
        Point startPoint = java.awt.MouseInfo.getPointerInfo().getLocation();
        currentAbsX = startPoint.x;
        currentAbsY = startPoint.y;

        // 获取主屏幕的分辨率
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        screenWidth = screenSize.width;
        screenHeight = screenSize.height;

        // 4. 定义底层消息拦截器 (当收到消息时，生成 Point 并放入队列)
        customWndProc = new WindowProc() {
            @Override
            public LRESULT callback(HWND h, int uMsg, WPARAM wParam, LPARAM lParam) {
                if (uMsg == WM_INPUT) {
                    // 提取原始输入数据
                    User32Ext.INSTANCE.GetRawInputData(lParam, RID_INPUT, null, pcbSize, HEADER_SIZE);
                    User32Ext.INSTANCE.GetRawInputData(lParam, RID_INPUT, rawInputBuffer, pcbSize, HEADER_SIZE);

                    if (rawInputBuffer.getInt(0) == RIM_TYPEMOUSE) { // 判断是否为鼠标数据
                        // 偏移量 12 和 16 分别是相对位移 lLastX 和 lLastY
                        int deltaX = rawInputBuffer.getInt(HEADER_SIZE + 12);
                        int deltaY = rawInputBuffer.getInt(HEADER_SIZE + 16);

                        /* TODO:增加过滤全零包的逻辑，暂不开放，后面用1个ConfigIO中已有的变量来控制是否过滤全零包
                        // 读取按键标志位 (usButtonFlags 在结构体中的偏移量是 4，占用 2 字节)
                        short buttonFlags = rawInputBuffer.getShort(HEADER_SIZE + 4);

                        // 全零包过滤逻辑，如果 X 和 Y 都没有移动，并且没有任何按键操作，则彻底抛弃这个包
                        if (deltaX == 0 && deltaY == 0 && buttonFlags == 0) {
                            // 这是为了填充 8K 轮询率产生的废包，直接跳过处理！
                        } else {
                            // TODO:这里将来如果开放，后面的的逻辑的记得屏蔽
                            // 将物理偏移量累加到我们的绝对坐标上,限制 X Y 坐标不超过屏幕的宽高后入队列
                            currentAbsX += deltaX;
                            currentAbsY += deltaY;
                            currentAbsX = Math.max(0, Math.min(screenWidth - 1, currentAbsX));
                            currentAbsY = Math.max(0, Math.min(screenHeight - 1, currentAbsY));
                            mouseEventQueue.offer(new Point((int)currentAbsX, (int)currentAbsY));
                        }
                        */

                        // TODO:将来如果做了过滤全零包的功能，这下面的逻辑记得删除
                        // 将物理偏移量累加到我们的绝对坐标上
                        currentAbsX += deltaX;
                        currentAbsY += deltaY;

                        // 限制 X Y 坐标不超过屏幕的宽高
                        currentAbsX = Math.max(0, Math.min(screenWidth - 1, currentAbsX));
                        currentAbsY = Math.max(0, Math.min(screenHeight - 1, currentAbsY));
                        
                        // 将计算好的绝对坐标转成 Point 放进队列供线程消费
                        mouseEventQueue.offer(new Point((int)currentAbsX, (int)currentAbsY));
                    }
                }
                // 重要：必须调用原有的 WndProc，否则 JFrame 会假死崩溃
                return User32Ext.INSTANCE.CallWindowProc(oldWndProc, h, uMsg, wParam, lParam);
            }
        };

        // 5. 将拦截器挂载到 JFrame 上 (兼容 32位和64位 JDK)
        if (Native.POINTER_SIZE == 8) {
            oldWndProc = User32Ext.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, customWndProc);
        } else {
            oldWndProc = User32Ext.INSTANCE.SetWindowLong(hwnd, GWLP_WNDPROC, customWndProc);
        }
    }

    @Override
    public void run() {
        // 新方法，先在线程启动时注册底层 Hook
        initNativeHook();

        //每秒更新一次轮询率的线程，此线程睡眠中被调用 interrupt 会立刻走 catch 所以 catch 里再等1s重走逻辑
        pollingRateMeasurerThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    //e.printStackTrace();
                     try {
                        mouseUpdateCounter = 0;
                        mouseEventQueue.clear();
                        Thread.sleep(1000);
                    } catch (InterruptedException e2) {
                    }
                }
                //每个捕获到的鼠标点都会被 accept 带一堆参数更新到 MainFrame 那边，但是轮训率这个参数每秒只更新一次
                currentPollingRate = mouseUpdateCounter;
                mouseUpdateCounter = 0;
                currentPollingRate = (currentPollingRate > 8000) ? 8000 : currentPollingRate;
            }
        }, "PollingRateMeasurer");
        pollingRateMeasurerThread.start();

        Point currentPosition = null;
        int pollSkipping = 1;

        // 自动校准的控制变量
        long lastInputTime = System.currentTimeMillis();
        boolean needsCalibration = false;

        while (!Thread.interrupted()) {
            while (paused) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // 旧方法，读取系统转化过后的绝对坐标
            //currentPosition = MouseInfo.getPointerInfo().getLocation();
            
            // 新方法，HOOK 读取 HID 底层原始 delta 报文转换成绝对坐标入队列，再从队列中读取消费 Point 对象
            currentPosition = mouseEventQueue.poll();
            
            long currentTime = System.currentTimeMillis();

            // if (mouseUpdate == null || !currentPosition.equals(mouseUpdate.getPosition())) {
            if (currentPosition != null) {
                mouseUpdate = new MouseUpdate(System.nanoTime(), currentPosition,
                        config.getDpi(), currentPollingRate, mouseUpdate, buttonsPressed.toArray(new Integer[0]));

                if (pollSkipping < config.getPollrateDivisor()) {
                    pollSkipping++;
                } else {
                    mouseUpdateCounter++;
                    positionConsumer.accept(mouseUpdate);
                    pollSkipping = 1;
                }
                // 核心防抖逻辑：只要收到了报文，永远重置最后移动时间！
                lastInputTime = currentTime;
                needsCalibration = true;
            } else {
                // ----- 队列为空，代表鼠标此刻停顿了 ----- 检查：如果之前动过(需要校准)，并且距离最后一次报文已经超过了 160ms
                if (needsCalibration && (currentTime - lastInputTime > 160)) {
                    // 1. 获取系统真实光标位置（这个耗时操作完美避开了高速画圈期）
                    java.awt.Point sysPoint = java.awt.MouseInfo.getPointerInfo().getLocation();

                    // 2. 强行跨线程覆写底层的绝对物理坐标！
                    // (因为 100ms 没收到报文，此时 JNA 线程处于绝对休眠期，此时覆写没有任何并发冲突风险)
                    currentAbsX = sysPoint.x;
                    currentAbsY = sysPoint.y;

                    // 3. 标记为已校准，只要鼠标不挪动，就绝不再执行获取
                    needsCalibration = false;
                }
            } 
        }
        pollingRateMeasurerThread.interrupt();
    }

    public float getRelativeClickLatency() {
        float latency = (this.lastRightClickPressed - this.lastLeftClickPressed) / 1000000f;
        if(latency > 1000 || latency < -1000) return 0;
        else return latency;
    }

    public float getClickInterval() {
        return this.lastClickInterval;
    }

    public float getClickDuration() {
        return this.lastClickDuration;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void resetMeasurerThread() {
        pollingRateMeasurerThread.interrupt();
    }

    @Override
    public void mouseClicked(MouseEvent e) {

    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() == 1) lastLeftClickPressed = System.nanoTime();
        else if (e.getButton() == 3) lastRightClickPressed = System.nanoTime();

        if (lastLeftClickPressed == 0 || lastLeftClickReleased == 0) this.lastClickInterval = 0;
        else this.lastClickInterval = (this.lastLeftClickPressed - this.lastLeftClickReleased) / 1000000f;

        buttonsPressed.add(e.getButton());
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.getButton() == 1) lastLeftClickReleased = System.nanoTime();
        if (lastLeftClickPressed == 0 || lastLeftClickReleased == 0) this.lastClickDuration = 0;
        else this.lastClickDuration = (this.lastLeftClickReleased - this.lastLeftClickPressed) / 1000000f;
        for (int i = 0; i < buttonsPressed.size(); i++) {
            if (buttonsPressed.get(i) == e.getButton()) {
                buttonsPressed.remove(i);
                return;
            }
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {

    }

    @Override
    public void mouseExited(MouseEvent e) {

    }
}
