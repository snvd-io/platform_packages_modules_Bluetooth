/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "test"

#include <gtest/gtest.h>
#include <log/log.h>

#include "bluetooth/log.h"
#include "truncating_buffer.h"

/// Captures the latest message generated by the android vlog
/// implementation.
static std::optional<__android_log_message> androidLogMessage;

/// Mask the implementation from liblog.
int __android_log_is_loggable(int /*prio*/, const char* /*tag*/,
                              int /*default_prio*/) {
  return true;
}

/// Mask the implementation from liblog.
void __android_log_write_log_message(
    struct __android_log_message* log_message) {
  if (log_message != nullptr) {
    log_message->message = strdup(log_message->message);
    androidLogMessage.emplace(*log_message);
  }
}

using namespace bluetooth;

TEST(BluetoothLogTest, verbose) {
  androidLogMessage.reset();

  log::verbose("verbose test");

  ASSERT_TRUE(androidLogMessage.has_value());
  EXPECT_EQ(androidLogMessage->priority, ANDROID_LOG_VERBOSE);
  EXPECT_STREQ(androidLogMessage->tag, LOG_TAG);
  EXPECT_EQ(androidLogMessage->file, nullptr);
  EXPECT_EQ(androidLogMessage->line, 0);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:49 TestBody: verbose test");
}

TEST(BluetoothLogTest, debug) {
  androidLogMessage.reset();

  log::debug("debug test");

  ASSERT_TRUE(androidLogMessage.has_value());
  EXPECT_EQ(androidLogMessage->priority, ANDROID_LOG_DEBUG);
  EXPECT_STREQ(androidLogMessage->tag, LOG_TAG);
  EXPECT_STREQ(androidLogMessage->file, nullptr);
  EXPECT_EQ(androidLogMessage->line, 0);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:63 TestBody: debug test");
}

TEST(BluetoothLogTest, info) {
  androidLogMessage.reset();

  log::info("info test");

  ASSERT_TRUE(androidLogMessage.has_value());
  EXPECT_EQ(androidLogMessage->priority, ANDROID_LOG_INFO);
  EXPECT_STREQ(androidLogMessage->tag, LOG_TAG);
  EXPECT_STREQ(androidLogMessage->file, nullptr);
  EXPECT_EQ(androidLogMessage->line, 0);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:77 TestBody: info test");
}

TEST(BluetoothLogTest, warn) {
  androidLogMessage.reset();

  log::warn("warn test");

  ASSERT_TRUE(androidLogMessage.has_value());
  EXPECT_EQ(androidLogMessage->priority, ANDROID_LOG_WARN);
  EXPECT_STREQ(androidLogMessage->tag, LOG_TAG);
  EXPECT_STREQ(androidLogMessage->file, nullptr);
  EXPECT_EQ(androidLogMessage->line, 0);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:91 TestBody: warn test");
}

TEST(BluetoothLogTest, error) {
  androidLogMessage.reset();

  log::error("error test");

  ASSERT_TRUE(androidLogMessage.has_value());
  EXPECT_EQ(androidLogMessage->priority, ANDROID_LOG_ERROR);
  EXPECT_STREQ(androidLogMessage->tag, LOG_TAG);
  EXPECT_STREQ(androidLogMessage->file, nullptr);
  EXPECT_EQ(androidLogMessage->line, 0);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:105 TestBody: error test");
}

TEST(BluetoothLogDeathTest, fatal) {
  androidLogMessage.reset();

  ASSERT_DEATH(
      {
        log::fatal("fatal test");
        // Validate that the compiler is correctly handling log::fatal as
        // [[noreturn]] by attempting to invoke an undefined function.
        // This test will fail linking if this check fails.
        void undefined_function();
        undefined_function();
      },
      "fatal test");

  ASSERT_DEATH(
      {
        log::fatal("fatal test {}", "2");
        void undefined_function();
        undefined_function();
      },
      "fatal test 2");

  ASSERT_DEATH(
      {
        log::fatal("fatal test {}, {}", 2, 3);
        void undefined_function();
        undefined_function();
      },
      "fatal test 2, 3");
}

TEST(BluetoothLogDeathTest, assert_that) {
  androidLogMessage.reset();

  log::assert_that(true, "assert_that test true");
  log::assert_that(true, "assert_that test {}", "true");

  ASSERT_DEATH(
      { log::assert_that(false, "assert_that test false"); },
      "assert_that test false");
}

TEST(BluetoothLogTest, null_string_parameter) {
  androidLogMessage.reset();

  char const* const_null_str = nullptr;
  log::info("input: {}", const_null_str);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:162 TestBody: input: (nullptr)");

  androidLogMessage.reset();

  char* null_str = nullptr;
  log::info("input: {}", null_str);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:169 TestBody: input: (nullptr)");

  androidLogMessage.reset();

  char const* nonnull_str = "hello world";
  log::info("input: {}", nonnull_str);
  EXPECT_STREQ(androidLogMessage->message,
               "system/log/src/vlog_test.cc:176 TestBody: input: hello world");
}
