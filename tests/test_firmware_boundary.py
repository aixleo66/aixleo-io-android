import unittest

import lab
from test_interactions import method


class FirmwareBoundaryChecks(unittest.TestCase):
    """固件通道只读边界（用户 2026-09-22 定：固件只分析、不碰）。

    固件通道只允许空负载状态查询，不允许发送更新数据：
    我方一切改动止于 App 层，不刷写、不 OTA、不升级降级。

    这条边界此前只靠 sendBusiness 里的守卫维持，没有任何测试锁住它。加测试的直接原因是
    2026-09-22 的调研发现第三方项目已经把 OTA 协议帧逆向清楚了（business 9，type 1-11，
    分片 51200 字节，授权窗口 900 秒），照着实现一条升级路径在技术上不难。守卫本身很严
    （负载被强制重写成 type 1 空包），但没有东西阻止后来的人多加一个调用点绕过它。

    所以这里钉的是**允许出现的位置**，不是守卫的写法——多一处引用就红，逼人工复核。
    """

    ALLOWED_CALL_SITES = 3  # 发起只读查询、收包分发、回包处理

    def test_the_firmware_channel_gains_no_new_call_sites(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        self.assertEqual(
            source.count('MARS_FOTA'), self.ALLOWED_CALL_SITES,
            '固件通道的引用点数量变了。这不一定是错的，但必须人工确认新增的用法仍然是只读：'
            '不得发起升级、不得传 type 1 以外的类型、不得携带负载。'
            '确认无误后再改本测试里的 ALLOWED_CALL_SITES。')

    def test_the_guard_still_pins_type_and_rewrites_the_payload(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        guard = method(source, 'private void sendBusiness(')
        self.assertIn('businessName.equals("MARS_FOTA")', guard)
        # 只读查询是 type 1；任何其它类型都必须被拒绝。
        self.assertIn('type!=1', guard)
        # 负载强制重写：即使调用方传了别的内容，发出去的也只能是 type 1 空包。
        # 这一行才是真正的硬边界——去掉它，上面的拒绝条件就只剩纪律。
        self.assertIn('payload=BusinessEnvelope.encode(1,"")', guard)

    def test_no_other_production_file_touches_the_firmware_channel(self):
        others = [path for path in lab.production_sources()
                  if path.name != 'SdkProbeActivity.java']
        self.assertGreater(len(others), 30, 'no production sources found')
        hits = sorted(path.name for path in others
                      if 'MARS_FOTA' in path.read_text(encoding='utf-8'))
        self.assertEqual(hits, [], '这些文件引用了固件通道，须确认未越过只读边界：%s' % hits)


if __name__ == '__main__':
    unittest.main()
