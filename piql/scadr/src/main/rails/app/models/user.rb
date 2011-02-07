class User < AvroRecord
  include Comparable
  
  # For password-checking, temporarily store the passwords entered in the form
  attr_accessor :plain_password
  attr_accessor :confirm_password

  def self.find(id)
    raw_users = User.find_user(id)
    raw_users.present? ? @user = raw_users.first.first : @user = nil
  end
  
  # If the form password is valid, crypt it and store it as the model password
  # Else, add errors
  def valid_password?
    self.errors.push [:password, "cannot be blank"] if plain_password.blank?
    self.errors.push [:password, "does not match"] if plain_password != confirm_password
    
    if self.errors.present?
      false
    else
      self.password = Digest::MD5.hexdigest(plain_password)
      true
    end
  end
  
  # Check if the password is valid before saving
  def save
    if valid_password?
      super
    else
      false
    end
  end

  def to_param
    username
  end

  def following(count)
    Subscription.users_followed_by(username, count)
  end

  def followers(count)
    Subscription.users_following(username, count)
  end

  def my_thoughts(count)
    Thought.my_thoughts(username, count)
  end

  def thoughtstream(count)
    Thought.thoughtstream(username, count)
  end
  
  def <=>(other)
    self.username <=> other.username if other.is_a?(User)
  end
  
  def errors
    @errors ||= []
  end
end
