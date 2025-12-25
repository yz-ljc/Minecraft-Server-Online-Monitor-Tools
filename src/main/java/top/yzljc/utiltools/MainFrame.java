package top.yzljc.utiltools;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainFrame extends JFrame {

    private final List<ServerInfo> serverList = Collections.synchronizedList(new ArrayList<>());
    private final DefaultTableModel tableModel;
    private final JTable table;
    private TrayIcon trayIcon;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MainFrame() {
        setTitle("MC Server Monitor (Pro Edition)");
        setSize(800, 500);
        // 点击关闭按钮时不默认退出，而是什么都不做（交给我们自己的监听器处理）
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        // 1. 初始化系统托盘 (核心：处理最小化图标和通知)
        initSystemTray();

        // 2. 初始化窗口监听 (核心：处理点击关闭按钮变为隐藏)
        initWindowListeners();

        // 3. 初始化菜单栏 (开机自启功能)
        initMenuBar();

        // --- 加载数据 ---
        List<ServerInfo> savedData = DataManager.load();
        serverList.addAll(savedData);

        // --- UI 构建 ---
        var topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        var nameField = new JTextField("Example", 8);
        var ipField = new JTextField("127.0.0.1", 12);
        var portField = new JTextField("25565", 5);

        var addButton = new JButton("添加");
        var deleteButton = new JButton("删除选中");
        deleteButton.setForeground(Color.RED);

        topPanel.add(new JLabel("名称:"));
        topPanel.add(nameField);
        topPanel.add(new JLabel("IP:"));
        topPanel.add(ipField);
        topPanel.add(new JLabel("端口:"));
        topPanel.add(portField);
        topPanel.add(addButton);
        topPanel.add(deleteButton);

        String[] columnNames = {"名称", "IP地址", "端口", "状态", "最后检测时间"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getColumnModel().getColumn(0).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        refreshTableUI();

        // --- 事件绑定 ---
        addButton.addActionListener(e -> {
            try {
                var name = nameField.getText().trim();
                var ip = ipField.getText().trim();
                var portStr = portField.getText().trim();

                if(name.isEmpty() || ip.isEmpty() || portStr.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "请填写完整信息");
                    return;
                }

                int port = Integer.parseInt(portStr);
                serverList.add(new ServerInfo(name, ip, port));
                DataManager.save(serverList);
                refreshTableUI();
                nameField.setText("");
                ipField.setText("");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "端口必须是数字");
            }
        });

        deleteButton.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow == -1) {
                JOptionPane.showMessageDialog(this, "请先选中要删除的行");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(this, "确定删除吗？", "确认", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                serverList.remove(selectedRow);
                DataManager.save(serverList);
                refreshTableUI();
            }
        });

        // 启动检测任务
        scheduler.scheduleAtFixedRate(this::runChecks, 0, 10, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------
    // 新增功能实现区域
    // ---------------------------------------------------------

    /**
     * 初始化窗口监听器：拦截关闭按钮，改为隐藏到托盘
     */
    private void initWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // 如果系统支持托盘，则隐藏窗口（最小化到托盘效果）
                if (SystemTray.isSupported()) {
                    setVisible(false);
                } else {
                    // 不支持托盘则直接退出
                    System.exit(0);
                }
            }
        });
    }

    /**
     * 初始化菜单栏（设置 - 开机自启）
     */
    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu settingsMenu = new JMenu("设置");

        JCheckBoxMenuItem autoStartItem = new JCheckBoxMenuItem("开机自启动");

        // 获取 jpackage 打包后的路径。如果在 IDE 中运行，此值为 null
        String appPath = System.getProperty("jpackage.app-path");

        if (appPath == null) {
            autoStartItem.setEnabled(false);
            autoStartItem.setToolTipText("此功能仅在打包为 EXE 后可用");
        }

        autoStartItem.addActionListener(e -> {
            toggleAutoStart(autoStartItem.isSelected());
        });

        settingsMenu.add(autoStartItem);
        menuBar.add(settingsMenu);
        setJMenuBar(menuBar);
    }

    /**
     * 执行开机自启注册表修改
     */
    private void toggleAutoStart(boolean enable) {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null) return;

        String cmd;
        try {
            if (enable) {
                // 注册表添加启动项
                cmd = String.format("reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\" /v \"McMonitor\" /d \"%s\" /f", appPath);
            } else {
                // 注册表删除启动项
                cmd = "reg delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\" /v \"McMonitor\" /f";
            }
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "设置开机自启失败: " + e.getMessage());
        }
    }

    /**
     * 初始化系统托盘
     */
    private void initSystemTray() {
        if (!SystemTray.isSupported()) return;
        try {
            var tray = SystemTray.getSystemTray();

            // 定义一个最终使用的 Image 变量
            Image finalImage;

            // 尝试加载自定义图标
            java.net.URL imgUrl = getClass().getResource("/app.png");

            if (imgUrl != null) {
                // 如果找到了图片，直接加载为 Image
                finalImage = Toolkit.getDefaultToolkit().getImage(imgUrl);
            } else {
                // 如果没找到图片，手动绘制一个
                // 修复点：显式使用 BufferedImage 类型，确保 createGraphics() 方法可用
                var bufferedImage = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var g = bufferedImage.createGraphics();
                g.setColor(new Color(60, 179, 113));
                g.fillRect(0, 0, 16, 16);
                g.dispose();

                // 将绘制好的 BufferedImage 赋值给最终变量
                finalImage = bufferedImage;
            }

            // 创建右键菜单
            PopupMenu popup = new PopupMenu();
            MenuItem showItem = new MenuItem("显示主界面");
            MenuItem exitItem = new MenuItem("退出程序");

            showItem.addActionListener(e -> {
                setVisible(true);
                setExtendedState(JFrame.NORMAL); // 恢复正常大小
                toFront(); // 置顶
            });

            exitItem.addActionListener(e -> {
                System.exit(0); // 彻底退出
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            // 使用最终确定的 finalImage
            trayIcon = new TrayIcon(finalImage, "MC Monitor", popup);
            trayIcon.setImageAutoSize(true);

            // 双击托盘图标恢复窗口
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        setVisible(true);
                        setExtendedState(JFrame.NORMAL);
                        toFront();
                    }
                }
            });

            tray.add(trayIcon);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------
    // 核心检测逻辑
    // ---------------------------------------------------------

    private void runChecks() {
        if (serverList.isEmpty()) return;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var server : serverList) {
                executor.submit(() -> checkServer(server));
            }
        }
        SwingUtilities.invokeLater(this::refreshTableUI);
    }

    private void checkServer(ServerInfo server) {
        boolean isOnlineNow;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(server.getIp(), server.getPort()), 3000);
            isOnlineNow = true;
        } catch (Exception e) {
            isOnlineNow = false;
        }

        boolean wasOnline = server.isOnline();
        boolean isFirst = server.isFirstCheck();

        // 状态发生改变
        if (isOnlineNow != wasOnline) {
            // 只有不是第一次启动检测时，才弹窗
            if (!isFirst) {
                if (isOnlineNow) {
                    // 离线 -> 在线 (蓝色 INFO)
                    sendNotification("服务器上线啦！",
                            "[" + server.getName() + "] 终于上线了，快去连接吧！", TrayIcon.MessageType.INFO);
                } else {
                    // 在线 -> 离线 (黄色 WARNING)
                    sendNotification("服务器掉线了...",
                            "[" + server.getName() + "] 刚刚断开了连接。", TrayIcon.MessageType.WARNING);
                }
            }
        }

        server.setOnline(isOnlineNow);
        server.setFirstCheck(false);
    }

    private void sendNotification(String title, String content, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, content, type);
        }
    }

    private void refreshTableUI() {
        int selectedRow = table.getSelectedRow();
        tableModel.setRowCount(0);
        synchronized (serverList) {
            var timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            for (var s : serverList) {
                tableModel.addRow(new Object[]{
                        s.getName(),
                        s.getIp(),
                        s.getPort(),
                        s.isOnline() ? "√ 在线" : "× 离线",
                        s.isFirstCheck() ? "等待检测..." : timeStr
                });
            }
        }
        if (selectedRow >= 0 && selectedRow < table.getRowCount()) {
            table.setRowSelectionInterval(selectedRow, selectedRow);
        }
    }
}
