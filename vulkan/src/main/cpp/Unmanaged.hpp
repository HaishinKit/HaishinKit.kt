#ifndef HAISHINKIT_KT_UNMANAGED_HPP
#define HAISHINKIT_KT_UNMANAGED_HPP

#include <jni.h>
#include <memory>
#include <string>
#include <map>

template<typename T>
class Unmanaged {
public:
    static Unmanaged<T> *FromOpaque(JNIEnv *env, jobject object) {
        thread_local std::unique_ptr<Unmanaged<T>> reference;
        reference.reset(new Unmanaged<T>(env, object));
        return reference.get();
    }

    explicit Unmanaged(JNIEnv *env, jobject object) : env(env), object(object) {
        fieldID = env->GetFieldID(env->GetObjectClass(object), "memory", "J");
    }

    template<typename Lambda>
    void *Safe(Lambda lambda) {
        try {
            Retain();
            return lambda(reinterpret_cast<T *>(env->GetLongField(object, fieldID)));
        } catch (std::runtime_error error) {
            jclass clazz = env->FindClass("java/lang/RuntimeException");
            if (clazz == nullptr) {
                return nullptr;
            }
            env->ThrowNew(clazz, error.what());
            env->DeleteLocalRef(clazz);
        } catch (std::exception &exception) {
            jclass clazz = env->FindClass("java/lang/Exception");
            if (clazz == nullptr) {
                return nullptr;
            }
            env->ThrowNew(clazz, exception.what());
            env->DeleteLocalRef(clazz);
        } catch (...) {
            jclass clazz = env->FindClass("java/lang/Exception");
            if (clazz == nullptr) {
                return nullptr;
            }
            env->ThrowNew(clazz, "");
            env->DeleteLocalRef(clazz);
        }
        return nullptr;
    }

    T *TakeRetainedValue() {
        Retain();
        return reinterpret_cast<T *>(env->GetLongField(object, fieldID));
    }

    void Release() {
        jlong address = env->GetLongField(object, fieldID);
        if (address != 0) {
            env->SetLongField(object, fieldID, reinterpret_cast<jlong>(nullptr));
            delete reinterpret_cast<T *>(address);


        }
    }

private:
    JNIEnv *env;
    jobject object;
    jfieldID fieldID;

    void Retain() {
        jlong address = env->GetLongField(object, fieldID);
        if (address == 0) {
            env->SetLongField(object, fieldID, reinterpret_cast<jlong>(new T()));
        }
    }


};


#endif //HAISHINKIT_KT_UNMANAGED_HPP
