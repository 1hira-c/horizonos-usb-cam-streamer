#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <map>
#include <memory>
#include <mutex>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char* kTag = "UsbDiagNative";

jstring make_string(JNIEnv* env, const char* text) {
  return env->NewStringUTF(text);
}

long long now_ms() {
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

bool set_alt(int fd, int interface_id, int alt_setting) {
  usbdevfs_setinterface set_interface{};
  set_interface.interface = static_cast<unsigned int>(interface_id);
  set_interface.altsetting = static_cast<unsigned int>(alt_setting);
  return ioctl(fd, USBDEVFS_SETINTERFACE, &set_interface) == 0;
}

bool reap_until_returned(int fd, usbdevfs_urb* target, int timeout_ms) {
  const long long deadline = now_ms() + timeout_ms;
  void* reaped = nullptr;
  while (now_ms() < deadline) {
    const int ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
    if (ret == 0 && reaped == target) {
      return true;
    }
    if (ret != 0 && errno != EAGAIN && errno != ENODATA) {
      return false;
    }
    usleep(2 * 1000);
  }
  return false;
}

void discard_and_reap(int fd, usbdevfs_urb* urb) {
  ioctl(fd, USBDEVFS_DISCARDURB, urb);
  if (!reap_until_returned(fd, urb, 250)) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "DISCARDURB did not reap target URB before cleanup");
  }
}

struct IsoSlot {
  usbdevfs_urb* urb = nullptr;
  unsigned char* data = nullptr;
  bool submitted = false;
};

struct IsoStreamSession {
  std::mutex mtx;  // 当該セッション(=fd)の read/stop を直列化する
  int fd = -1;
  int interface_id = -1;
  int alt_setting = 0;
  int endpoint_address = 0;
  int packet_size = 0;
  int packets_per_urb = 0;
  bool claimed = false;
  bool active = false;
  std::vector<IsoSlot> slots;
  long long started_ms = 0;
  long long last_log_ms = 0;
  long long submitted_urbs = 0;
  long long reaped_urbs = 0;
  long long packet_records = 0;
  long long payload_bytes = 0;
  long long timeouts = 0;
  long long reap_errors = 0;
};

// fd ごとに 1 セッション。複数カメラの同時ストリーミングを成立させる。
// g_sessions_mutex は map 構造（探索/挿入/削除）のみを保護し、各セッションの
// reap ループは session->mtx で保護する（map ロックは短時間しか保持しない）。
std::mutex g_sessions_mutex;
std::map<int, std::shared_ptr<IsoStreamSession>> g_sessions;

int urb_alloc_bytes(int packets_per_urb) {
  return static_cast<int>(sizeof(usbdevfs_urb)) +
      static_cast<int>(sizeof(usbdevfs_iso_packet_desc)) * packets_per_urb;
}

void init_iso_urb(usbdevfs_urb* urb,
                  unsigned char* data,
                  int endpoint_address,
                  int packet_size,
                  int packets_per_urb) {
  memset(urb, 0, urb_alloc_bytes(packets_per_urb));
  memset(data, 0, packet_size * packets_per_urb);
  urb->type = USBDEVFS_URB_TYPE_ISO;
  urb->endpoint = static_cast<unsigned char>(endpoint_address);
  urb->flags = USBDEVFS_URB_ISO_ASAP;
  urb->buffer = data;
  urb->buffer_length = packet_size * packets_per_urb;
  urb->number_of_packets = packets_per_urb;
  for (int i = 0; i < packets_per_urb; ++i) {
    urb->iso_frame_desc[i].length = static_cast<unsigned int>(packet_size);
  }
}

bool submit_slot(IsoStreamSession& session, IsoSlot& slot) {
  init_iso_urb(slot.urb,
               slot.data,
               session.endpoint_address,
               session.packet_size,
               session.packets_per_urb);
  if (ioctl(session.fd, USBDEVFS_SUBMITURB, slot.urb) < 0) {
    return false;
  }
  slot.submitted = true;
  session.submitted_urbs++;
  return true;
}

IsoSlot* find_slot(IsoStreamSession& session, void* urb) {
  for (auto& slot : session.slots) {
    if (slot.urb == urb) {
      return &slot;
    }
  }
  return nullptr;
}

void log_stream_stats_if_due(IsoStreamSession& session, bool force) {
  const long long now = now_ms();
  if (!force && now - session.last_log_ms < 1000) {
    return;
  }
  const long long elapsed_ms = (now - session.started_ms) > 0 ? now - session.started_ms : 1;
  const double seconds = static_cast<double>(elapsed_ms) / 1000.0;
  __android_log_print(
      ANDROID_LOG_INFO,
      kTag,
      "isoStream stats: queued=%zu submitted=%lld reaped/s=%.1f packets/s=%.1f payloadKB/s=%.1f timeout=%lld errors=%lld",
      session.slots.size(),
      session.submitted_urbs,
      static_cast<double>(session.reaped_urbs) / seconds,
      static_cast<double>(session.packet_records) / seconds,
      static_cast<double>(session.payload_bytes) / 1024.0 / seconds,
      session.timeouts,
      session.reap_errors);
  session.last_log_ms = now;
}

void cleanup_stream_locked(IsoStreamSession& session) {
  if (session.fd >= 0) {
    for (auto& slot : session.slots) {
      if (slot.submitted && slot.urb != nullptr) {
        discard_and_reap(session.fd, slot.urb);
        slot.submitted = false;
      }
    }
  }
  for (auto& slot : session.slots) {
    free(slot.urb);
    free(slot.data);
  }
  session.slots.clear();
  if (session.fd >= 0 && session.interface_id >= 0) {
    set_alt(session.fd, session.interface_id, 0);
    if (session.claimed) {
      int claimed_interface = session.interface_id;
      ioctl(session.fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    }
  }
  // セッションは shared_ptr の破棄で消える。ここでは active/claimed を落とすに留める
  // （std::mutex を持つため struct 全体の再代入はできない）。
  session.claimed = false;
  session.active = false;
}

jstring make_stream_status(JNIEnv* env, const IsoStreamSession& session, const char* prefix) {
  const long long elapsed_ms = session.started_ms > 0 ? (now_ms() - session.started_ms) : 0;
  char buf[256];
  snprintf(buf,
           sizeof(buf),
           "%s active=%d queued=%zu submitted=%lld reaped=%lld packets=%lld payload=%lldB timeouts=%lld errors=%lld elapsed=%lldms",
           prefix,
           session.active ? 1 : 0,
           session.slots.size(),
           session.submitted_urbs,
           session.reaped_urbs,
           session.packet_records,
           session.payload_bytes,
           session.timeouts,
           session.reap_errors,
           elapsed_ms);
  return make_string(env, buf);
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_jp_hitohira_usbcamstreamer_usb_UvcNative_nativeStatus(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd) {
  if (fd < 0) {
    return make_string(env, "fd is invalid (<0)");
  }

  errno = 0;
  const int flags = fcntl(fd, F_GETFD);
  if (flags < 0) {
    char buf[128];
    snprintf(buf, sizeof(buf), "fcntl(F_GETFD) failed: errno=%d %s", errno, strerror(errno));
    return make_string(env, buf);
  }

  const int dup_fd = dup(fd);
  if (dup_fd < 0) {
    char buf[128];
    snprintf(buf, sizeof(buf), "fd ok, dup failed: errno=%d %s", errno, strerror(errno));
    return make_string(env, buf);
  }
  close(dup_fd);

  __android_log_print(ANDROID_LOG_INFO, kTag, "fd=%d flags=%d dup=%d", fd, flags, dup_fd);
  char buf[128];
  snprintf(buf, sizeof(buf), "fd ok: fd=%d flags=%d dup=%d", fd, flags, dup_fd);
  return make_string(env, buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_hitohira_usbcamstreamer_usb_UvcNative_isoSmokeTest(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd,
    jint interface_id,
    jint alt_setting,
    jint endpoint_address,
    jint packet_size,
    jint packet_count) {
  if (fd < 0 || packet_size <= 0 || packet_count <= 0) {
    return make_string(env, "invalid arguments for iso smoke test");
  }

  int claimed_interface = interface_id;
  if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &claimed_interface) < 0 && errno != EBUSY) {
    char buf[160];
    snprintf(buf, sizeof(buf), "CLAIMINTERFACE if=%d failed: errno=%d %s",
             interface_id, errno, strerror(errno));
    return make_string(env, buf);
  }

  usbdevfs_setinterface set_interface{};
  set_interface.interface = static_cast<unsigned int>(interface_id);
  set_interface.altsetting = static_cast<unsigned int>(alt_setting);
  if (ioctl(fd, USBDEVFS_SETINTERFACE, &set_interface) < 0) {
    const int saved_errno = errno;
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    char buf[160];
    snprintf(buf, sizeof(buf), "SETINTERFACE if=%d alt=%d failed: errno=%d %s",
             interface_id, alt_setting, saved_errno, strerror(saved_errno));
    return make_string(env, buf);
  }

  const int urb_bytes = static_cast<int>(sizeof(usbdevfs_urb)) +
      static_cast<int>(sizeof(usbdevfs_iso_packet_desc)) * packet_count;
  auto* urb = static_cast<usbdevfs_urb*>(calloc(1, urb_bytes));
  auto* data = static_cast<unsigned char*>(calloc(1, packet_size * packet_count));
  if (urb == nullptr || data == nullptr) {
    free(urb);
    free(data);
    usbdevfs_setinterface restore{};
    restore.interface = static_cast<unsigned int>(interface_id);
    restore.altsetting = 0;
    ioctl(fd, USBDEVFS_SETINTERFACE, &restore);
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    return make_string(env, "allocation failed for iso smoke test");
  }

  urb->type = USBDEVFS_URB_TYPE_ISO;
  urb->endpoint = static_cast<unsigned char>(endpoint_address);
  urb->flags = USBDEVFS_URB_ISO_ASAP;
  urb->buffer = data;
  urb->buffer_length = packet_size * packet_count;
  urb->number_of_packets = packet_count;
  for (int i = 0; i < packet_count; ++i) {
    urb->iso_frame_desc[i].length = static_cast<unsigned int>(packet_size);
  }

  if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) {
    const int saved_errno = errno;
    free(urb);
    free(data);
    usbdevfs_setinterface restore{};
    restore.interface = static_cast<unsigned int>(interface_id);
    restore.altsetting = 0;
    ioctl(fd, USBDEVFS_SETINTERFACE, &restore);
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    char buf[180];
    snprintf(buf, sizeof(buf), "SUBMITURB iso ep=0x%02x failed: errno=%d %s",
             endpoint_address, saved_errno, strerror(saved_errno));
    return make_string(env, buf);
  }

  void* reaped = nullptr;
  int reap_ret = -1;
  int saved_errno = 0;
  for (int tries = 0; tries < 100; ++tries) {
    reap_ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
    if (reap_ret == 0) {
      break;
    }
    saved_errno = errno;
    if (errno != EAGAIN && errno != ENODATA) {
      break;
    }
    usleep(10 * 1000);
  }

  char result[240];
  if (reap_ret == 0 && reaped == urb) {
    int ok_packets = 0;
    int actual = 0;
    for (int i = 0; i < packet_count; ++i) {
      if (urb->iso_frame_desc[i].status == 0) {
        ok_packets++;
      }
      actual += static_cast<int>(urb->iso_frame_desc[i].actual_length);
    }
    snprintf(result, sizeof(result),
             "iso URB reaped: status=%d actual=%d/%d packetsOk=%d/%d firstStatus=%d",
             urb->status, actual, urb->buffer_length, ok_packets, packet_count,
             packet_count > 0 ? static_cast<int>(urb->iso_frame_desc[0].status) : 0);
  } else {
    discard_and_reap(fd, urb);
    snprintf(result, sizeof(result),
             "iso URB reap failed/timeout: ret=%d errno=%d %s",
             reap_ret, saved_errno, strerror(saved_errno));
  }

  free(urb);
  free(data);

  usbdevfs_setinterface restore{};
  restore.interface = static_cast<unsigned int>(interface_id);
  restore.altsetting = 0;
  ioctl(fd, USBDEVFS_SETINTERFACE, &restore);
  ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);

  return make_string(env, result);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_jp_hitohira_usbcamstreamer_usb_UvcNative_captureIsoPackets(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd,
    jint interface_id,
    jint alt_setting,
    jint endpoint_address,
    jint packet_size,
    jint packets_per_urb,
    jint max_output_bytes,
    jint timeout_ms) {
  if (fd < 0 || packet_size <= 0 || packets_per_urb <= 0 ||
      max_output_bytes <= 0 || timeout_ms <= 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "captureIsoPackets invalid arguments");
    return nullptr;
  }

  int claimed_interface = interface_id;
  if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &claimed_interface) < 0 && errno != EBUSY) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "CLAIMINTERFACE if=%d failed errno=%d %s",
                        interface_id, errno, strerror(errno));
    return nullptr;
  }

  if (!set_alt(fd, interface_id, alt_setting)) {
    const int saved_errno = errno;
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "SETINTERFACE if=%d alt=%d failed errno=%d %s",
                        interface_id, alt_setting, saved_errno, strerror(saved_errno));
    return nullptr;
  }

  const int urb_bytes = static_cast<int>(sizeof(usbdevfs_urb)) +
      static_cast<int>(sizeof(usbdevfs_iso_packet_desc)) * packets_per_urb;
  auto* urb = static_cast<usbdevfs_urb*>(calloc(1, urb_bytes));
  auto* data = static_cast<unsigned char*>(calloc(1, packet_size * packets_per_urb));
  if (urb == nullptr || data == nullptr) {
    free(urb);
    free(data);
    set_alt(fd, interface_id, 0);
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "captureIsoPackets allocation failed");
    return nullptr;
  }

  std::vector<unsigned char> output;
  output.reserve(static_cast<size_t>(max_output_bytes));
  int submitted_urbs = 0;
  int reaped_urbs = 0;
  int packet_records = 0;
  int payload_bytes = 0;
  const long long deadline = now_ms() + timeout_ms;

  while (now_ms() < deadline &&
         static_cast<int>(output.size()) + 2 < max_output_bytes) {
    memset(urb, 0, urb_bytes);
    memset(data, 0, packet_size * packets_per_urb);
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = static_cast<unsigned char>(endpoint_address);
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = data;
    urb->buffer_length = packet_size * packets_per_urb;
    urb->number_of_packets = packets_per_urb;
    for (int i = 0; i < packets_per_urb; ++i) {
      urb->iso_frame_desc[i].length = static_cast<unsigned int>(packet_size);
    }

    if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SUBMITURB capture failed errno=%d %s",
                          errno, strerror(errno));
      break;
    }
    submitted_urbs++;

    void* reaped = nullptr;
    int reap_ret = -1;
    while (now_ms() < deadline) {
      reap_ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
      if (reap_ret == 0 || (errno != EAGAIN && errno != ENODATA)) {
        break;
      }
      usleep(2 * 1000);
    }

    if (reap_ret != 0 || reaped != urb) {
      const int saved_errno = errno;
      discard_and_reap(fd, urb);
      if (saved_errno != EAGAIN && saved_errno != ENODATA) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "REAPURB capture stopped ret=%d errno=%d %s",
                            reap_ret, saved_errno, strerror(saved_errno));
      }
      break;
    }
    reaped_urbs++;

    for (int i = 0; i < packets_per_urb; ++i) {
      const auto& frame = urb->iso_frame_desc[i];
      const int actual = frame.status == 0 ? static_cast<int>(frame.actual_length) : 0;
      if (actual < 0 || actual > packet_size) {
        continue;
      }
      if (static_cast<int>(output.size()) + 2 + actual > max_output_bytes) {
        break;
      }
      output.push_back(static_cast<unsigned char>(actual & 0xFF));
      output.push_back(static_cast<unsigned char>((actual >> 8) & 0xFF));
      if (actual > 0) {
        const int offset = i * packet_size;
        output.insert(output.end(), data + offset, data + offset + actual);
      }
      packet_records++;
      payload_bytes += actual;
    }
  }

  free(urb);
  free(data);
  set_alt(fd, interface_id, 0);
  ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);

  __android_log_print(ANDROID_LOG_INFO, kTag,
                      "captureIsoPackets submitted=%d reaped=%d packets=%d payload=%d out=%zu",
                      submitted_urbs, reaped_urbs, packet_records, payload_bytes, output.size());

  auto result = env->NewByteArray(static_cast<jsize>(output.size()));
  if (result == nullptr) {
    return nullptr;
  }
  if (!output.empty()) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                            reinterpret_cast<const jbyte*>(output.data()));
  }
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_hitohira_usbcamstreamer_usb_UvcNative_startIsoStream(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd,
    jint interface_id,
    jint alt_setting,
    jint endpoint_address,
    jint packet_size,
    jint packets_per_urb,
    jint urb_count) {
  if (fd < 0 || packet_size <= 0 || packets_per_urb <= 0 || urb_count <= 0) {
    return make_string(env, "invalid arguments for iso stream");
  }

  std::lock_guard<std::mutex> lock(g_sessions_mutex);
  // 同一 fd の既存セッションが残っていれば畳んでから作り直す。
  auto existing = g_sessions.find(fd);
  if (existing != g_sessions.end()) {
    std::lock_guard<std::mutex> slock(existing->second->mtx);
    cleanup_stream_locked(*existing->second);
    g_sessions.erase(existing);
  }

  auto session = std::make_shared<IsoStreamSession>();
  session->fd = fd;
  session->interface_id = interface_id;
  session->alt_setting = alt_setting;
  session->endpoint_address = endpoint_address;
  session->packet_size = packet_size;
  session->packets_per_urb = packets_per_urb;
  session->started_ms = now_ms();
  session->last_log_ms = session->started_ms;

  int claimed_interface = interface_id;
  if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &claimed_interface) < 0 && errno != EBUSY) {
    char buf[160];
    snprintf(buf, sizeof(buf), "CLAIMINTERFACE if=%d failed: errno=%d %s",
             interface_id, errno, strerror(errno));
    return make_string(env, buf);
  }
  session->claimed = true;

  if (!set_alt(fd, interface_id, alt_setting)) {
    const int saved_errno = errno;
    if (session->claimed) {
      ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claimed_interface);
    }
    char buf[160];
    snprintf(buf, sizeof(buf), "SETINTERFACE if=%d alt=%d failed: errno=%d %s",
             interface_id, alt_setting, saved_errno, strerror(saved_errno));
    return make_string(env, buf);
  }

  const int safe_urb_count = urb_count < 1 ? 1 : (urb_count > 16 ? 16 : urb_count);
  const int safe_packets_per_urb =
      packets_per_urb < 1 ? 1 : (packets_per_urb > 64 ? 64 : packets_per_urb);
  session->packets_per_urb = safe_packets_per_urb;
  session->slots.resize(static_cast<size_t>(safe_urb_count));
  const int urb_bytes_value = urb_alloc_bytes(safe_packets_per_urb);
  const int data_bytes = packet_size * safe_packets_per_urb;

  for (auto& slot : session->slots) {
    slot.urb = static_cast<usbdevfs_urb*>(calloc(1, urb_bytes_value));
    slot.data = static_cast<unsigned char*>(calloc(1, data_bytes));
    if (slot.urb == nullptr || slot.data == nullptr) {
      cleanup_stream_locked(*session);
      return make_string(env, "allocation failed for iso stream");
    }
    if (!submit_slot(*session, slot)) {
      const int saved_errno = errno;
      cleanup_stream_locked(*session);
      char buf[180];
      snprintf(buf, sizeof(buf), "SUBMITURB stream failed: errno=%d %s",
               saved_errno, strerror(saved_errno));
      return make_string(env, buf);
    }
  }

  session->active = true;
  __android_log_print(ANDROID_LOG_INFO,
                      kTag,
                      "isoStream started: fd=%d if=%d alt=%d ep=0x%02x packet=%d packetsPerUrb=%d urbCount=%d streams=%zu",
                      fd,
                      interface_id,
                      alt_setting,
                      endpoint_address,
                      packet_size,
                      safe_packets_per_urb,
                      safe_urb_count,
                      g_sessions.size() + 1);
  jstring status = make_stream_status(env, *session, "started");
  g_sessions[fd] = std::move(session);
  return status;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_jp_hitohira_usbcamstreamer_usb_UvcNative_readIsoStream(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd,
    jint max_output_bytes,
    jint timeout_ms) {
  if (fd < 0 || max_output_bytes <= 0 || timeout_ms <= 0) {
    return nullptr;
  }

  // map ロックはセッション取得だけ。reap ループは session->mtx で保護する。
  std::shared_ptr<IsoStreamSession> session;
  {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(fd);
    if (it == g_sessions.end()) {
      return nullptr;
    }
    session = it->second;
  }
  std::lock_guard<std::mutex> slock(session->mtx);
  if (!session->active || session->fd < 0) {
    return nullptr;
  }

  std::vector<unsigned char> output;
  output.reserve(static_cast<size_t>(max_output_bytes));
  const long long deadline = now_ms() + timeout_ms;
  bool had_timeout = false;

  while (now_ms() < deadline &&
         static_cast<int>(output.size()) + 2 < max_output_bytes) {
    void* reaped = nullptr;
    const int ret = ioctl(session->fd, USBDEVFS_REAPURBNDELAY, &reaped);
    if (ret != 0) {
      const int saved_errno = errno;
      if (saved_errno == EAGAIN || saved_errno == ENODATA) {
        had_timeout = true;
        usleep(1000);
        continue;
      }
      session->reap_errors++;
      __android_log_print(ANDROID_LOG_WARN,
                          kTag,
                          "REAPURB stream stopped fd=%d ret=%d errno=%d %s",
                          session->fd,
                          ret,
                          saved_errno,
                          strerror(saved_errno));
      break;
    }

    IsoSlot* slot = find_slot(*session, reaped);
    if (slot == nullptr) {
      session->reap_errors++;
      __android_log_print(ANDROID_LOG_WARN, kTag, "REAPURB stream returned unknown URB fd=%d", session->fd);
      continue;
    }

    slot->submitted = false;
    session->reaped_urbs++;
    for (int i = 0; i < session->packets_per_urb; ++i) {
      const auto& frame = slot->urb->iso_frame_desc[i];
      const int actual = frame.status == 0 ? static_cast<int>(frame.actual_length) : 0;
      if (actual < 0 || actual > session->packet_size) {
        continue;
      }
      if (static_cast<int>(output.size()) + 2 + actual > max_output_bytes) {
        break;
      }
      output.push_back(static_cast<unsigned char>(actual & 0xFF));
      output.push_back(static_cast<unsigned char>((actual >> 8) & 0xFF));
      if (actual > 0) {
        const int offset = i * session->packet_size;
        output.insert(output.end(), slot->data + offset, slot->data + offset + actual);
      }
      session->packet_records++;
      session->payload_bytes += actual;
    }

    if (!submit_slot(*session, *slot)) {
      const int saved_errno = errno;
      session->reap_errors++;
      __android_log_print(ANDROID_LOG_WARN,
                          kTag,
                          "SUBMITURB stream resubmit failed fd=%d errno=%d %s",
                          session->fd,
                          saved_errno,
                          strerror(saved_errno));
      break;
    }
  }

  if (had_timeout && output.empty()) {
    session->timeouts++;
  }
  log_stream_stats_if_due(*session, false);

  auto result = env->NewByteArray(static_cast<jsize>(output.size()));
  if (result == nullptr) {
    return nullptr;
  }
  if (!output.empty()) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                            reinterpret_cast<const jbyte*>(output.data()));
  }
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_hitohira_usbcamstreamer_usb_UvcNative_stopIsoStream(
    JNIEnv* env,
    jobject /* thiz */,
    jint fd) {
  std::shared_ptr<IsoStreamSession> session;
  {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(fd);
    if (it == g_sessions.end()) {
      return make_string(env, "stopped active=0");
    }
    session = it->second;
    g_sessions.erase(it);
  }
  std::lock_guard<std::mutex> slock(session->mtx);
  log_stream_stats_if_due(*session, true);
  jstring status = make_stream_status(env, *session, "stopped");
  cleanup_stream_locked(*session);
  return status;
}
